package com.example.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.remote.UcnSyncRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ContactSyncRuntimeState(
    val isSyncing: Boolean = false,
    val lastTrigger: String = "idle",
    val lastMatchedFriendsCount: Int = 0,
    val lastScannedContactsCount: Int = 0,
    val lastSyncAtMs: Long = 0L,
    val lastError: String? = null
)

object ContactSyncStatusHolder {
    private val _state = MutableStateFlow(ContactSyncRuntimeState())
    val state: StateFlow<ContactSyncRuntimeState> = _state.asStateFlow()

    fun update(transform: (ContactSyncRuntimeState) -> ContactSyncRuntimeState) {
        _state.value = transform(_state.value)
    }
}

class ContactSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSyncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundSafely()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val triggerReason = intent?.getStringExtra(EXTRA_TRIGGER_REASON) ?: "manual"
        promoteToForeground()

        // Coalesce concurrent sync requests cleanly
        if (activeSyncJob?.isActive == true) {
            return START_NOT_STICKY
        }

        activeSyncJob = serviceScope.launch {
            try {
                runSyncCycle(applicationContext, triggerReason)
            } finally {
                stopForegroundSafely()
                stopSelfResult(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to promote ContactSyncService to foreground: ${e.message}")
        }
    }

    private fun stopForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "UCN Contact Discovery",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Privacy-preserving SHA-256 contact matching with UCN friends"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            102,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("UCN Friend Discovery")
            .setContentText("Syncing hashed contact identifiers with Firestore...")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "ContactSyncService"
        private const val CHANNEL_ID = "ucn_contact_sync"
        private const val NOTIFICATION_ID = 4402
        const val ACTION_SYNC = "com.example.sync.ACTION_SYNC_CONTACTS"
        const val ACTION_STOP = "com.example.sync.ACTION_STOP_CONTACTS"
        const val EXTRA_TRIGGER_REASON = "extra_trigger_reason"

        fun startSync(context: Context, reason: String = "manual") {
            val intent = Intent(context, ContactSyncService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_TRIGGER_REASON, reason)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service start blocked ($reason): ${e.message}")
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun stopSync(context: Context) {
            val intent = Intent(context, ContactSyncService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        suspend fun runSyncCycle(context: Context, reason: String): Result<ContactSyncSummary> {
            val auth = Firebase.auth
            val currentUser = auth.currentUser
            if (currentUser == null) {
                val err = IllegalStateException("Sign in with Google to sync contacts.")
                ContactSyncStatusHolder.update {
                    it.copy(isSyncing = false, lastTrigger = reason, lastError = err.message)
                }
                return Result.failure(err)
            }

            if (!DeviceContactsReader.hasContactsPermission(context)) {
                val err = SecurityException("Contacts permission not granted.")
                ContactSyncStatusHolder.update {
                    it.copy(isSyncing = false, lastTrigger = reason, lastError = err.message)
                }
                return Result.failure(err)
            }

            val prefsStore = UserPreferencesDataStore(context)
            val prefs = prefsStore.preferencesFlow.first()
            if (prefs.offlineSimulationMode) {
                val err = IllegalStateException("Offline simulation mode enabled; contact discovery deferred.")
                ContactSyncStatusHolder.update {
                    it.copy(isSyncing = false, lastTrigger = reason, lastError = err.message)
                }
                return Result.failure(err)
            }

            ContactSyncStatusHolder.update {
                it.copy(isSyncing = true, lastTrigger = reason, lastError = null)
            }

            val databaseId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            val repo = UcnSyncRepository(db, auth)

            val deviceContacts = try {
                DeviceContactsReader.readContacts(context)
            } catch (e: Exception) {
                ContactSyncStatusHolder.update {
                    it.copy(isSyncing = false, lastTrigger = reason, lastError = e.localizedMessage ?: "Failed to read device contacts")
                }
                return Result.failure(e)
            }

            val result = repo.syncDeviceContactsWithFirestore(currentUser.uid, deviceContacts)
            val now = System.currentTimeMillis()
            result.onSuccess { summary ->
                prefsStore.recordContactSyncStats(now, summary.scannedContactsCount)
                ContactSyncStatusHolder.update {
                    it.copy(
                        isSyncing = false,
                        lastTrigger = reason,
                        lastMatchedFriendsCount = summary.matchedFriendsCount,
                        lastScannedContactsCount = summary.scannedContactsCount,
                        lastSyncAtMs = now,
                        lastError = null
                    )
                }
            }.onFailure { err ->
                ContactSyncStatusHolder.update {
                    it.copy(
                        isSyncing = false,
                        lastTrigger = reason,
                        lastError = err.localizedMessage ?: "Contact sync failed"
                    )
                }
            }
            return result
        }
    }
}
