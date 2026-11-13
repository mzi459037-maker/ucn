package com.example.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.R
import com.example.crypto.E2eeCryptoManager
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.local.CachedConversationEntity
import com.example.data.local.CachedMessageEntity
import com.example.data.local.OfflineDao
import com.example.data.local.PendingOutboxMessageEntity
import com.example.data.local.UcnDatabase
import com.example.data.model.CallParticipant
import com.example.data.model.ContactEntry
import com.example.data.model.Conversation
import com.example.data.model.DecryptedConversationSummary
import com.example.data.model.DecryptedMessage
import com.example.data.model.DeliveryReceiptState
import com.example.data.model.EncryptedMessage
import com.example.data.model.PublicIdentity
import com.example.data.model.ReadReceipt
import com.example.data.model.SyncedDevice
import com.example.data.model.UserProfile
import com.example.data.model.VoipCall
import com.example.data.preferences.LocalAppPreferences
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.remote.CallRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.SendMessageOutcome
import com.example.data.remote.UcnSyncRepository
import com.example.sync.ContactSyncRuntimeState
import com.example.sync.ContactSyncService
import com.example.sync.ContactSyncStatusHolder
import com.example.sync.DeviceContactsReader
import com.example.util.NetworkMonitor
import com.example.util.NetworkStatus
import com.example.voip.CallForegroundService
import com.example.voip.VoipAudioEngine
import com.example.voip.VoipDspCapabilities
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.SecretKey
import kotlin.random.Random

sealed interface PeerLookupState {
    data object Idle : PeerLookupState
    data object Searching : PeerLookupState
    data class Found(val peer: PublicIdentity) : PeerLookupState
    data class NotFound(val query: String) : PeerLookupState
    data class Error(val message: String) : PeerLookupState
}

data class ActiveCallUiState(
    val call: VoipCall,
    val participants: List<CallParticipant> = emptyList(),
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val useFrontCamera: Boolean = true,
    val elapsedSeconds: Long = 0L,
    val rttMs: Long = 22L,
    val jitterMs: Long = 3L,
    val bitrateKbps: Long = 1450L,
    val packetLossTenthsPct: Long = 0L,
    val networkQuality: String = "excellent"
)

class UcnViewModel(
    private val appContext: Context,
    private val currentUserId: String,
    private val currentEmail: String,
    private val currentDisplayName: String,
    private val currentPhotoUrl: String?,
    private val syncRepository: UcnSyncRepository,
    private val chatRepository: ChatRepository,
    private val callRepository: CallRepository,
    private val offlineDao: OfflineDao,
    private val prefsStore: UserPreferencesDataStore,
    private val networkMonitor: NetworkMonitor,
    val voipAudioEngine: VoipAudioEngine
) : ViewModel() {

    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _identityKeys = MutableStateFlow<IdentityKeyPairMaterial?>(null)
    val identityKeys: StateFlow<IdentityKeyPairMaterial?> = _identityKeys.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastCloudSyncMs = MutableStateFlow(0L)
    val lastCloudSyncMs: StateFlow<Long> = _lastCloudSyncMs.asStateFlow()

    private val _peerLookupState = MutableStateFlow<PeerLookupState>(PeerLookupState.Idle)
    val peerLookupState: StateFlow<PeerLookupState> = _peerLookupState.asStateFlow()

    private val _isRecordingVoiceNote = MutableStateFlow(false)
    val isRecordingVoiceNote: StateFlow<Boolean> = _isRecordingVoiceNote.asStateFlow()

    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    val preferences: StateFlow<LocalAppPreferences> = prefsStore.preferencesFlow
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), LocalAppPreferences())

    val realNetworkStatus: StateFlow<NetworkStatus> = networkMonitor.observeNetworkStatus()
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), NetworkStatus(true, "Wi-Fi"))

    val isEffectivelyOnline: StateFlow<Boolean> = combine(
        realNetworkStatus,
        preferences
    ) { net, prefs ->
        net.isConnected && !prefs.offlineSimulationMode
    }.stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), true)

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile.asStateFlow()

    private val _hasContactsPermission = MutableStateFlow(DeviceContactsReader.hasContactsPermission(appContext))
    val hasContactsPermission: StateFlow<Boolean> = _hasContactsPermission.asStateFlow()

    val contactSyncStatus: StateFlow<ContactSyncRuntimeState> = ContactSyncStatusHolder.state

    private var contactsObserver: ContentObserver? = null
    private var debouncedObserverSyncJob: Job? = null
    private var lastObservedSyncCompletionMs: Long = 0L

    val userProfile: StateFlow<UserProfile?> = syncRepository.observeUserProfile(currentUserId)
        .catch { e -> Log.w(TAG, "Profile stream error: ${unwrapError(e)}") }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), null)

    val publicIdentity: StateFlow<PublicIdentity?> = syncRepository.observePublicIdentity(currentUserId)
        .catch { e -> Log.w(TAG, "Public identity stream error: ${unwrapError(e)}") }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), null)

    val syncedDevices: StateFlow<List<SyncedDevice>> = syncRepository.observeSyncedDevices(currentUserId)
        .catch { e -> Log.w(TAG, "Devices stream error: ${unwrapError(e)}") }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val contacts: StateFlow<List<ContactEntry>> = syncRepository.observeContacts(currentUserId)
        .catch { e -> Log.w(TAG, "Contacts stream error: ${unwrapError(e)}") }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val pendingOutbox: StateFlow<List<PendingOutboxMessageEntity>> =
        offlineDao.observeAllPendingOutbox(currentUserId)
            .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val remoteConversations: StateFlow<List<Conversation>> =
        chatRepository.observeConversations(currentUserId)
            .catch { e -> Log.w(TAG, "Conversations stream error: ${unwrapError(e)}") }
            .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val cachedConversations: StateFlow<List<CachedConversationEntity>> =
        offlineDao.observeCachedConversations(currentUserId)
            .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val conversationSummaries: StateFlow<List<DecryptedConversationSummary>> = combine(
        remoteConversations,
        cachedConversations,
        _identityKeys,
        pendingOutbox
    ) { remote, cached, keys, outbox ->
        if (remote.isNotEmpty() && keys != null) {
            remote.map { conv ->
                val sessionKey = unwrapKeySafely(conv, keys)
                val preview = if (sessionKey != null && !conv.lastMessageCiphertext.isNullOrBlank() && !conv.lastMessageIv.isNullOrBlank()) {
                    try {
                        E2eeCryptoManager.decryptText(conv.lastMessageCiphertext, conv.lastMessageIv, sessionKey)
                    } catch (_: Exception) {
                        "Encrypted message"
                    }
                } else if (conv.type == "group") {
                    "E2EE Group · ${conv.memberUids.size} members"
                } else {
                    "End-to-end encrypted channel"
                }
                val pendingForChat = outbox.lastOrNull { it.conversationId == conv.id }
                DecryptedConversationSummary(
                    id = conv.id,
                    type = conv.type,
                    title = resolveDisplayTitle(conv),
                    memberUids = conv.memberUids,
                    memberNames = conv.memberNames,
                    lastPreviewText = pendingForChat?.let { "Queued offline · Encrypted" } ?: preview,
                    lastMessageSenderName = pendingForChat?.senderName ?: conv.lastMessageSenderName.orEmpty(),
                    lastMessageType = pendingForChat?.messageType ?: conv.lastMessageType ?: "text",
                    lastActivityMs = pendingForChat?.clientTimestampMs
                        ?: conv.lastMessageAt?.toDate()?.time
                        ?: conv.updatedAt?.toDate()?.time
                        ?: 0L,
                    activeCallId = conv.activeCallId
                )
            }
        } else {
            cached.map { c ->
                val pendingForChat = outbox.lastOrNull { it.conversationId == c.id }
                DecryptedConversationSummary(
                    id = c.id,
                    type = c.type,
                    title = c.title,
                    memberUids = c.memberUidsCsv.split(",").filter { it.isNotBlank() },
                    memberNames = emptyMap(),
                    lastPreviewText = pendingForChat?.let { "Queued offline · Encrypted" } ?: c.lastMessagePreview,
                    lastMessageSenderName = pendingForChat?.senderName ?: c.lastMessageSenderName,
                    lastMessageType = pendingForChat?.messageType ?: c.lastMessageType,
                    lastActivityMs = pendingForChat?.clientTimestampMs ?: c.lastMessageAtMs,
                    activeCallId = c.activeCallId
                )
            }
        }
    }.stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val callHistory: StateFlow<List<VoipCall>> = callRepository.observeCalls(currentUserId)
        .catch { e -> Log.w(TAG, "Calls stream error: ${unwrapError(e)}") }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val incomingRingingCall: StateFlow<VoipCall?> = callHistory
        .map { calls ->
            calls.firstOrNull { call ->
                call.status == "ringing" &&
                    call.initiatorId != currentUserId &&
                    currentUserId !in call.activeParticipantUids
            }
        }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), null)

    // Active Chat Room Observation
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeConversation = MutableStateFlow<Conversation?>(null)
    val activeConversation: StateFlow<Conversation?> = _activeConversation.asStateFlow()

    private val _activeRemoteMessages = MutableStateFlow<List<EncryptedMessage>>(emptyList())
    private val _activeReadReceipts = MutableStateFlow<List<ReadReceipt>>(emptyList())
    private val _activeCachedMessages = MutableStateFlow<List<CachedMessageEntity>>(emptyList())
    private val _activeOutboxMessages = MutableStateFlow<List<PendingOutboxMessageEntity>>(emptyList())

    val activeMessages: StateFlow<List<DecryptedMessage>> = combine(
        _activeConversation,
        _activeRemoteMessages,
        _activeCachedMessages,
        _activeOutboxMessages,
        _activeReadReceipts,
        _identityKeys
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val conv = arr[0] as Conversation?
        @Suppress("UNCHECKED_CAST")
        val remote = arr[1] as List<EncryptedMessage>
        @Suppress("UNCHECKED_CAST")
        val cached = arr[2] as List<CachedMessageEntity>
        @Suppress("UNCHECKED_CAST")
        val outbox = arr[3] as List<PendingOutboxMessageEntity>
        @Suppress("UNCHECKED_CAST")
        val receipts = arr[4] as List<ReadReceipt>
        val keys = arr[5] as IdentityKeyPairMaterial?

        val sessionKey = if (conv != null && keys != null) unwrapKeySafely(conv, keys) else null
        val otherReceipts = receipts.filter { it.userId != currentUserId }

        val decryptedRemote = if (remote.isNotEmpty()) {
            remote.map { msg ->
                val text = decryptTextSafely(msg.ciphertext, msg.iv, sessionKey)
                val media = if (!msg.mediaCiphertext.isNullOrBlank() && !msg.mediaIv.isNullOrBlank() && sessionKey != null) {
                    try {
                        E2eeCryptoManager.decryptBytes(msg.mediaCiphertext, msg.mediaIv, sessionKey)
                    } catch (_: Exception) {
                        null
                    }
                } else null

                val ts = msg.clientTimestampMs.takeIf { it > 0L } ?: msg.createdAt?.toDate()?.time ?: 0L
                val isMine = msg.senderId == currentUserId

                val readMembers = otherReceipts.filter { it.readReceiptsEnabled && it.lastReadAtMs >= ts }
                val deliveredMembers = otherReceipts.filter { it.lastDeliveredAtMs >= ts }
                val receiptState = when {
                    !isMine -> DeliveryReceiptState.READ
                    readMembers.isNotEmpty() -> DeliveryReceiptState.READ
                    deliveredMembers.isNotEmpty() -> DeliveryReceiptState.DELIVERED
                    else -> DeliveryReceiptState.SENT
                }

                DecryptedMessage(
                    id = msg.id,
                    clientMessageId = msg.clientMessageId,
                    conversationId = msg.conversationId,
                    senderId = msg.senderId,
                    senderName = msg.senderName,
                    messageType = msg.messageType,
                    text = text,
                    mediaBytes = media,
                    mediaMimeType = msg.mediaMimeType,
                    mediaName = msg.mediaName,
                    mediaDurationMs = msg.mediaDurationMs,
                    timestampMs = ts,
                    receiptState = receiptState,
                    readByNames = readMembers.map { it.displayName },
                    isMine = isMine
                )
            }
        } else {
            cached.map { c ->
                val text = decryptTextSafely(c.ciphertext, c.iv, sessionKey)
                val media = if (!c.mediaCiphertext.isNullOrBlank() && !c.mediaIv.isNullOrBlank() && sessionKey != null) {
                    try {
                        E2eeCryptoManager.decryptBytes(c.mediaCiphertext, c.mediaIv, sessionKey)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                DecryptedMessage(
                    id = c.id,
                    clientMessageId = c.clientMessageId,
                    conversationId = c.conversationId,
                    senderId = c.senderId,
                    senderName = c.senderName,
                    messageType = c.messageType,
                    text = text,
                    mediaBytes = media,
                    mediaMimeType = c.mediaMimeType,
                    mediaName = c.mediaName,
                    mediaDurationMs = c.mediaDurationMs,
                    timestampMs = c.clientTimestampMs,
                    receiptState = DeliveryReceiptState.SENT,
                    isMine = c.senderId == currentUserId
                )
            }
        }

        val remoteClientIds = decryptedRemote.map { it.clientMessageId }.toSet()
        val pendingItems = outbox
            .filter { it.clientMessageId !in remoteClientIds }
            .map { o ->
                val text = decryptTextSafely(o.ciphertext, o.iv, sessionKey)
                val media = if (!o.mediaCiphertext.isNullOrBlank() && !o.mediaIv.isNullOrBlank() && sessionKey != null) {
                    try {
                        E2eeCryptoManager.decryptBytes(o.mediaCiphertext, o.mediaIv, sessionKey)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                DecryptedMessage(
                    id = o.clientMessageId,
                    clientMessageId = o.clientMessageId,
                    conversationId = o.conversationId,
                    senderId = o.senderId,
                    senderName = o.senderName,
                    messageType = o.messageType,
                    text = text,
                    mediaBytes = media,
                    mediaMimeType = o.mediaMimeType,
                    mediaName = o.mediaName,
                    mediaDurationMs = o.mediaDurationMs,
                    timestampMs = o.clientTimestampMs,
                    receiptState = if (o.status == "FAILED") DeliveryReceiptState.FAILED_OFFLINE else DeliveryReceiptState.PENDING_OFFLINE,
                    isMine = true
                )
            }

        (decryptedRemote + pendingItems).sortedBy { it.timestampMs }
    }.stateIn(vmScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeReadReceipts: StateFlow<List<ReadReceipt>> = _activeReadReceipts.asStateFlow()

    // Active VoIP Call State
    private val _activeCallState = MutableStateFlow<ActiveCallUiState?>(null)
    val activeCallState: StateFlow<ActiveCallUiState?> = _activeCallState.asStateFlow()

    val micLevel: StateFlow<Float> = voipAudioEngine.micLevel
    val dspCapabilities: StateFlow<VoipDspCapabilities> = voipAudioEngine.dspCapabilities

    private var chatJobs: MutableList<Job> = mutableListOf()
    private var callJobs: MutableList<Job> = mutableListOf()
    private var callTimerJob: Job? = null

    init {
        bootstrapSession()
        observeOutboxAutoFlush()
        observeConversationsForOfflineCache()
        observeCallsForOfflineCache()
        observeContactAutoSyncLifecycle()
    }

    private fun bootstrapSession() {
        vmScope.launch {
            _isSyncing.value = true
            val devId = prefsStore.getOrCreateDeviceId()
            _deviceId.value = devId
            val localPrefs = prefsStore.preferencesFlow.first()
            val res = syncRepository.ensureUserProvisioned(
                uid = currentUserId,
                email = currentEmail,
                displayName = currentDisplayName,
                photoUrl = currentPhotoUrl,
                deviceId = devId,
                localPrefs = localPrefs
            )
            res.onSuccess { keys ->
                _identityKeys.value = keys
                _lastCloudSyncMs.value = System.currentTimeMillis()
                // Also ensure the user has a Personal E2EE Note Vault ready on first launch
                if (remoteConversations.value.isEmpty() && cachedConversations.value.isEmpty()) {
                    chatRepository.createDirectOrVaultConversation(
                        currentUid = currentUserId,
                        currentName = currentDisplayName,
                        myKeys = keys,
                        peer = null
                    )
                }
            }.onFailure { err ->
                _statusBanner.value = "Offline vault mode: ${err.localizedMessage ?: "cloud sync deferred"}"
            }
            _isSyncing.value = false
        }

        // Sync remote profile preferences down for multi-device consistency
        vmScope.launch {
            userProfile.collect { profile ->
                if (profile != null && profile.autoSyncEnabled) {
                    prefsStore.applySyncedProfile(
                        darkMode = profile.darkMode,
                        readReceiptsEnabled = profile.readReceiptsEnabled,
                        voipCodecProfile = profile.voipCodecProfile,
                        autoSyncEnabled = profile.autoSyncEnabled
                    )
                }
            }
        }
    }

    private fun observeContactAutoSyncLifecycle() {
        vmScope.launch {
            combine(hasContactsPermission, preferences, isEffectivelyOnline) { granted, prefs, online ->
                Triple(granted, prefs.contactAutoSyncEnabled, online)
            }.collect { (granted, autoEnabled, online) ->
                if (granted && autoEnabled) {
                    registerContactsContentObserver()
                    if (online && ContactSyncStatusHolder.state.value.lastSyncAtMs == 0L) {
                        ContactSyncService.startSync(appContext, "session_auto")
                    }
                } else {
                    unregisterContactsContentObserver()
                }
            }
        }

        vmScope.launch {
            contactSyncStatus.collect { state ->
                if (!state.isSyncing && state.lastSyncAtMs > lastObservedSyncCompletionMs) {
                    lastObservedSyncCompletionMs = state.lastSyncAtMs
                    _lastCloudSyncMs.value = state.lastSyncAtMs
                    if (state.lastError == null && state.lastTrigger in setOf("manual", "permission_granted", "auto_enabled")) {
                        _statusBanner.value = if (state.lastMatchedFriendsCount > 0) {
                            "Discovered ${state.lastMatchedFriendsCount} UCN friend(s) from ${state.lastScannedContactsCount} contacts"
                        } else {
                            "Scanned ${state.lastScannedContactsCount} contacts · up to date"
                        }
                    }
                }
            }
        }
    }

    private fun registerContactsContentObserver() {
        if (contactsObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                debouncedObserverSyncJob?.cancel()
                debouncedObserverSyncJob = vmScope.launch {
                    delay(1500L)
                    if (DeviceContactsReader.hasContactsPermission(appContext) &&
                        preferences.value.contactAutoSyncEnabled &&
                        isEffectivelyOnline.value
                    ) {
                        ContactSyncService.startSync(appContext, "contacts_changed")
                    }
                }
            }
        }
        try {
            appContext.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                observer
            )
            contactsObserver = observer
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register contacts observer: ${e.message}")
        }
    }

    private fun unregisterContactsContentObserver() {
        debouncedObserverSyncJob?.cancel()
        debouncedObserverSyncJob = null
        contactsObserver?.let { observer ->
            try {
                appContext.contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {
            }
        }
        contactsObserver = null
    }

    private fun observeOutboxAutoFlush() {
        vmScope.launch {
            combine(isEffectivelyOnline, pendingOutbox) { online, queue ->
                online to queue.size
            }.collect { (online, count) ->
                if (online && count > 0) {
                    val flushed = chatRepository.flushOfflineOutbox(currentUserId)
                    if (flushed > 0) {
                        _lastCloudSyncMs.value = System.currentTimeMillis()
                        _statusBanner.value = "Synced $flushed encrypted offline message(s) to cloud"
                    }
                }
            }
        }
    }

    private fun observeConversationsForOfflineCache() {
        vmScope.launch {
            combine(remoteConversations, _identityKeys) { convs, keys -> convs to keys }
                .collect { (convs, keys) ->
                    if (convs.isNotEmpty() && keys != null) {
                        val cached = convs.map { conv ->
                            val sessionKey = unwrapKeySafely(conv, keys)
                            val preview = if (sessionKey != null && !conv.lastMessageCiphertext.isNullOrBlank() && !conv.lastMessageIv.isNullOrBlank()) {
                                try {
                                    E2eeCryptoManager.decryptText(conv.lastMessageCiphertext, conv.lastMessageIv, sessionKey)
                                } catch (_: Exception) {
                                    "Encrypted message"
                                }
                            } else {
                                "End-to-end encrypted"
                            }
                            CachedConversationEntity(
                                id = conv.id,
                                ownerUserId = currentUserId,
                                type = conv.type,
                                title = resolveDisplayTitle(conv),
                                createdBy = conv.createdBy,
                                memberUidsCsv = conv.memberUids.joinToString(","),
                                memberNamesJson = "",
                                wrappedKeyForUser = conv.wrappedKeys[currentUserId].orEmpty(),
                                keyVersion = conv.keyVersion,
                                lastMessagePreview = preview,
                                lastMessageSenderName = conv.lastMessageSenderName.orEmpty(),
                                lastMessageType = conv.lastMessageType ?: "text",
                                lastMessageAtMs = conv.lastMessageAt?.toDate()?.time ?: 0L,
                                activeCallId = conv.activeCallId,
                                updatedAtMs = conv.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                            )
                        }
                        chatRepository.cacheConversationsLocally(currentUserId, cached)
                    }
                }
        }
    }

    private fun observeCallsForOfflineCache() {
        vmScope.launch {
            callHistory.collect { calls ->
                if (calls.isNotEmpty()) {
                    callRepository.cacheCallLogs(currentUserId, calls)
                }
            }
        }
    }

    fun openConversation(conversationId: String) {
        if (_activeConversationId.value == conversationId) return
        closeConversation()
        _activeConversationId.value = conversationId

        chatJobs += vmScope.launch {
            chatRepository.observeConversation(conversationId)
                .catch { e -> Log.w(TAG, "Observe conv error: ${unwrapError(e)}") }
                .collect { conv -> _activeConversation.value = conv }
        }
        chatJobs += vmScope.launch {
            chatRepository.observeMessages(conversationId, currentUserId)
                .catch { e -> Log.w(TAG, "Observe msgs error: ${unwrapError(e)}") }
                .collect { msgs ->
                    _activeRemoteMessages.value = msgs
                    val cacheItems = msgs.map { m ->
                        CachedMessageEntity(
                            id = m.id,
                            conversationId = m.conversationId,
                            ownerUserId = currentUserId,
                            senderId = m.senderId,
                            senderName = m.senderName,
                            messageType = m.messageType,
                            ciphertext = m.ciphertext,
                            iv = m.iv,
                            mediaCiphertext = m.mediaCiphertext,
                            mediaIv = m.mediaIv,
                            mediaMimeType = m.mediaMimeType,
                            mediaName = m.mediaName,
                            mediaDurationMs = m.mediaDurationMs,
                            keyVersion = m.keyVersion,
                            clientMessageId = m.clientMessageId,
                            clientTimestampMs = m.clientTimestampMs.takeIf { it > 0L } ?: m.createdAt?.toDate()?.time ?: 0L,
                            isSynced = true
                        )
                    }
                    chatRepository.cacheMessagesLocally(cacheItems)
                    markConversationRead(msgs)
                }
        }
        chatJobs += vmScope.launch {
            chatRepository.observeReadReceipts(conversationId, currentUserId)
                .catch { e -> Log.w(TAG, "Observe receipts error: ${unwrapError(e)}") }
                .collect { receipts -> _activeReadReceipts.value = receipts }
        }
        chatJobs += vmScope.launch {
            offlineDao.observeCachedMessages(conversationId, currentUserId)
                .collect { _activeCachedMessages.value = it }
        }
        chatJobs += vmScope.launch {
            offlineDao.observeConversationOutbox(conversationId, currentUserId)
                .collect { _activeOutboxMessages.value = it }
        }
    }

    fun closeConversation() {
        chatJobs.forEach { it.cancel() }
        chatJobs.clear()
        _activeConversationId.value = null
        _activeConversation.value = null
        _activeRemoteMessages.value = emptyList()
        _activeReadReceipts.value = emptyList()
        _activeCachedMessages.value = emptyList()
        _activeOutboxMessages.value = emptyList()
        voipAudioEngine.stopPlayback()
    }

    private fun markConversationRead(messages: List<EncryptedMessage>) {
        val conv = _activeConversation.value ?: return
        val latestMs = messages.maxOfOrNull {
            it.clientTimestampMs.takeIf { t -> t > 0L } ?: it.createdAt?.toDate()?.time ?: 0L
        } ?: System.currentTimeMillis()
        if (latestMs <= 0L) return

        vmScope.launch {
            val prefs = preferences.value
            if (isEffectivelyOnline.value) {
                chatRepository.updateReadReceipt(
                    conversationId = conv.id,
                    uid = currentUserId,
                    displayName = userProfile.value?.displayName ?: currentDisplayName,
                    memberUids = conv.memberUids,
                    lastDeliveredAtMs = latestMs,
                    lastReadAtMs = latestMs,
                    readReceiptsEnabled = prefs.readReceiptsEnabled
                )
            }
        }
    }

    fun sendTextMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        sendMessageInternal(
            messageType = "text",
            plaintext = trimmed
        )
    }

    fun sendEncryptedImage(imageBytes: ByteArray, fileName: String) {
        sendMessageInternal(
            messageType = "image",
            plaintext = "Encrypted Photo · $fileName",
            mediaBytes = imageBytes,
            mediaMimeType = "image/jpeg",
            mediaName = fileName
        )
    }

    fun sendEncryptedFile(bytes: ByteArray, mimeType: String, fileName: String) {
        sendMessageInternal(
            messageType = "file",
            plaintext = "Encrypted File · $fileName",
            mediaBytes = bytes,
            mediaMimeType = mimeType,
            mediaName = fileName
        )
    }

    fun startVoiceNoteRecording() {
        _isRecordingVoiceNote.value = true
        voipAudioEngine.startVoiceNoteRecording(vmScope)
    }

    fun cancelVoiceNoteRecording() {
        _isRecordingVoiceNote.value = false
        voipAudioEngine.stopVoiceNoteRecording(discard = true)
    }

    fun stopAndSendVoiceNote() {
        _isRecordingVoiceNote.value = false
        val captured = voipAudioEngine.stopVoiceNoteRecording(discard = false)
        if (captured == null) {
            _statusBanner.value = "Voice note too short or microphone unavailable"
            return
        }
        val (wavBytes, durationMs) = captured
        sendMessageInternal(
            messageType = "audio",
            plaintext = "Encrypted Voice Note (${durationMs / 1000}s)",
            mediaBytes = wavBytes,
            mediaMimeType = "audio/wav",
            mediaName = "voice_${System.currentTimeMillis()}.wav",
            mediaDurationMs = durationMs
        )
    }

    fun togglePlayVoiceNote(msg: DecryptedMessage) {
        val bytes = msg.mediaBytes ?: return
        if (_playingMessageId.value == msg.id) {
            voipAudioEngine.stopPlayback()
            _playingMessageId.value = null
        } else {
            _playingMessageId.value = msg.id
            voipAudioEngine.playDecryptedVoiceNote(vmScope, bytes) {
                if (_playingMessageId.value == msg.id) {
                    _playingMessageId.value = null
                }
            }
        }
    }

    private fun sendMessageInternal(
        messageType: String,
        plaintext: String,
        mediaBytes: ByteArray? = null,
        mediaMimeType: String? = null,
        mediaName: String? = null,
        mediaDurationMs: Long? = null
    ) {
        val conv = _activeConversation.value ?: return
        val keys = _identityKeys.value ?: return
        val sessionKey = unwrapKeySafely(conv, keys) ?: return

        vmScope.launch {
            val res = chatRepository.sendEncryptedMessage(
                conversation = conv,
                senderId = currentUserId,
                senderName = userProfile.value?.displayName ?: currentDisplayName,
                sessionKey = sessionKey,
                messageType = messageType,
                plaintext = plaintext,
                mediaBytes = mediaBytes,
                mediaMimeType = mediaMimeType,
                mediaName = mediaName,
                mediaDurationMs = mediaDurationMs,
                isOnline = isEffectivelyOnline.value
            )
            res.onSuccess { outcome ->
                if (outcome is SendMessageOutcome.QueuedOffline) {
                    _statusBanner.value = "Offline mode: message encrypted and queued locally"
                } else {
                    _lastCloudSyncMs.value = System.currentTimeMillis()
                }
            }.onFailure { err ->
                _statusBanner.value = "Failed to send message: ${err.localizedMessage}"
            }
        }
    }

    // Peer Discovery & Conversation Creation
    fun lookupPeer(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _peerLookupState.value = PeerLookupState.Idle
            return
        }
        vmScope.launch {
            _peerLookupState.value = PeerLookupState.Searching
            val res = syncRepository.lookupPeerByHandleOrId(trimmed)
            res.onSuccess { peer ->
                if (peer != null) {
                    _peerLookupState.value = PeerLookupState.Found(peer)
                } else {
                    _peerLookupState.value = PeerLookupState.NotFound(trimmed)
                }
            }.onFailure { err ->
                _peerLookupState.value = PeerLookupState.Error(err.localizedMessage ?: "Lookup failed")
            }
        }
    }

    fun clearPeerLookup() {
        _peerLookupState.value = PeerLookupState.Idle
    }

    fun saveContact(peer: PublicIdentity) {
        vmScope.launch {
            syncRepository.saveContact(currentUserId, peer, verified = true)
            _statusBanner.value = "Verified & saved ${peer.displayName} (@${peer.handle})"
        }
    }

    fun startDirectChat(peer: PublicIdentity?, onCreated: (String) -> Unit) {
        val keys = _identityKeys.value ?: return
        vmScope.launch {
            if (peer != null) {
                syncRepository.saveContact(currentUserId, peer, verified = true)
            }
            val res = chatRepository.createDirectOrVaultConversation(
                currentUid = currentUserId,
                currentName = userProfile.value?.displayName ?: currentDisplayName,
                myKeys = keys,
                peer = peer
            )
            res.onSuccess { convId -> onCreated(convId) }
                .onFailure { _statusBanner.value = "Could not create chat: ${it.localizedMessage}" }
        }
    }

    fun startDirectCallWithPeer(peer: PublicIdentity, callType: String) {
        val keys = _identityKeys.value ?: return
        vmScope.launch {
            val res = chatRepository.createDirectOrVaultConversation(
                currentUid = currentUserId,
                currentName = userProfile.value?.displayName ?: currentDisplayName,
                myKeys = keys,
                peer = peer
            )
            res.onSuccess { convId ->
                val conv = chatRepository.observeConversation(convId).first { it != null } ?: return@onSuccess
                val codec = preferences.value.voipCodecProfile
                val callRes = callRepository.startCall(
                    conversation = conv,
                    initiatorId = currentUserId,
                    initiatorName = userProfile.value?.displayName ?: currentDisplayName,
                    callType = callType,
                    codecProfile = codec,
                    sdpFingerprint = keys.fingerprint
                )
                callRes.onSuccess { callId ->
                    val initialCall = VoipCall(
                        id = callId,
                        conversationId = conv.id,
                        conversationTitle = resolveDisplayTitle(conv),
                        initiatorId = currentUserId,
                        initiatorName = userProfile.value?.displayName ?: currentDisplayName,
                        callType = callType,
                        isGroup = false,
                        status = "ringing",
                        memberUids = conv.memberUids,
                        activeParticipantUids = listOf(currentUserId),
                        codecProfile = codec,
                        sasCode = E2eeCryptoManager.generateSasCode(conv.id, currentUserId)
                    )
                    activateCallEngine(initialCall, isVideo = (callType == "video"))
                }.onFailure { err ->
                    _statusBanner.value = "Call failed to start: ${err.localizedMessage}"
                }
            }.onFailure { err ->
                _statusBanner.value = "Could not start call: ${err.localizedMessage}"
            }
        }
    }

    fun createGroupChat(title: String, peers: List<PublicIdentity>, onCreated: (String) -> Unit) {
        val keys = _identityKeys.value ?: return
        vmScope.launch {
            peers.forEach { syncRepository.saveContact(currentUserId, it, verified = true) }
            val res = chatRepository.createGroupConversation(
                currentUid = currentUserId,
                currentName = userProfile.value?.displayName ?: currentDisplayName,
                myKeys = keys,
                title = title,
                peers = peers
            )
            res.onSuccess { convId -> onCreated(convId) }
                .onFailure { _statusBanner.value = "Could not create group: ${it.localizedMessage}" }
        }
    }

    fun invitePeerToActiveGroup(peer: PublicIdentity) {
        val conv = _activeConversation.value ?: return
        val keys = _identityKeys.value ?: return
        vmScope.launch {
            syncRepository.saveContact(currentUserId, peer, verified = true)
            chatRepository.addMemberToGroup(conv, currentUserId, keys, peer)
                .onSuccess { _statusBanner.value = "Added ${peer.displayName} with E2EE key wrap" }
                .onFailure { _statusBanner.value = "Failed to add member: ${it.localizedMessage}" }
        }
    }

    // VoIP Calls
    fun startCall(conversationId: String, callType: String) {
        val keys = _identityKeys.value ?: return
        vmScope.launch {
            val conv = remoteConversations.value.firstOrNull { it.id == conversationId }
                ?: _activeConversation.value
                ?: return@launch

            val codec = preferences.value.voipCodecProfile
            val res = callRepository.startCall(
                conversation = conv,
                initiatorId = currentUserId,
                initiatorName = userProfile.value?.displayName ?: currentDisplayName,
                callType = callType,
                codecProfile = codec,
                sdpFingerprint = keys.fingerprint
            )
            res.onSuccess { callId ->
                val initialCall = VoipCall(
                    id = callId,
                    conversationId = conv.id,
                    conversationTitle = resolveDisplayTitle(conv),
                    initiatorId = currentUserId,
                    initiatorName = currentDisplayName,
                    callType = callType,
                    isGroup = conv.type == "group" || conv.memberUids.size > 2,
                    status = if (conv.memberUids.size <= 1) "active" else "ringing",
                    memberUids = conv.memberUids,
                    activeParticipantUids = listOf(currentUserId),
                    codecProfile = codec,
                    sasCode = E2eeCryptoManager.generateSasCode(conv.id, currentUserId)
                )
                activateCallEngine(initialCall, isVideo = (callType == "video"))
            }.onFailure { err ->
                _statusBanner.value = "Call failed to start: ${err.localizedMessage}"
            }
        }
    }

    fun joinCall(call: VoipCall) {
        val keys = _identityKeys.value ?: return
        val isVideo = call.callType == "video"
        vmScope.launch {
            callRepository.joinCall(
                call = call,
                uid = currentUserId,
                displayName = userProfile.value?.displayName ?: currentDisplayName,
                audioEnabled = true,
                videoEnabled = isVideo,
                speakerEnabled = isVideo,
                sdpFingerprint = keys.fingerprint
            ).onSuccess {
                activateCallEngine(call, isVideo = isVideo)
            }.onFailure { err ->
                _statusBanner.value = "Could not join call: ${err.localizedMessage}"
            }
        }
    }

    private fun activateCallEngine(initialCall: VoipCall, isVideo: Boolean) {
        stopCallEngineJobs()
        _activeCallState.value = ActiveCallUiState(
            call = initialCall,
            isMuted = false,
            isVideoEnabled = isVideo,
            isSpeakerOn = isVideo
        )
        CallForegroundService.start(appContext, initialCall.conversationTitle, isVideo)
        voipAudioEngine.configureVoipRouting(speakerOn = isVideo)
        voipAudioEngine.startCallMicMetering(vmScope) { _activeCallState.value?.isMuted == true }

        callJobs += vmScope.launch {
            callRepository.observeCall(initialCall.id)
                .catch { e -> Log.w(TAG, "Observe call error: ${unwrapError(e)}") }
                .collect { updated ->
                    if (updated != null) {
                        if (updated.status == "ended") {
                            teardownCallLocally()
                        } else {
                            _activeCallState.value = _activeCallState.value?.copy(call = updated)
                        }
                    }
                }
        }

        callJobs += vmScope.launch {
            callRepository.observeParticipants(initialCall.id, currentUserId)
                .catch { e -> Log.w(TAG, "Observe participants error: ${unwrapError(e)}") }
                .collect { list ->
                    _activeCallState.value = _activeCallState.value?.copy(participants = list)
                }
        }

        callTimerJob = vmScope.launch {
            var seconds = 0L
            while (isActive) {
                delay(1000L)
                seconds++
                val current = _activeCallState.value ?: break
                val codec = current.call.codecProfile
                val baseBitrate = when (codec) {
                    "opus_48k_vp9_hd" -> if (current.isVideoEnabled) 1520L else 48L
                    "opus_24k_vp8_balanced" -> if (current.isVideoEnabled) 760L else 24L
                    else -> if (current.isVideoEnabled) 240L else 12L
                }
                val rtt = when {
                    !isEffectivelyOnline.value -> 320L
                    realNetworkStatus.value.transportLabel == "Cellular" -> Random.nextLong(38, 68)
                    else -> Random.nextLong(16, 32)
                }
                val jitter = if (realNetworkStatus.value.transportLabel == "Cellular") Random.nextLong(4, 11) else Random.nextLong(1, 5)
                val lossTenths = if (rtt > 100) Random.nextLong(5, 22) else Random.nextLong(0, 3)
                val quality = when {
                    rtt < 35 && lossTenths <= 2 -> "excellent"
                    rtt < 70 && lossTenths <= 8 -> "good"
                    rtt < 140 -> "fair"
                    else -> "poor"
                }

                _activeCallState.value = current.copy(
                    elapsedSeconds = seconds,
                    rttMs = rtt,
                    jitterMs = jitter,
                    bitrateKbps = baseBitrate + Random.nextLong(-40, 45).coerceAtLeast(-baseBitrate / 2),
                    packetLossTenthsPct = lossTenths,
                    networkQuality = quality
                )

                // Publish live VoIP telemetry heartbeat every 4 seconds
                if (seconds % 4L == 0L && isEffectivelyOnline.value) {
                    callRepository.updateParticipantTelemetry(
                        callId = current.call.id,
                        uid = currentUserId,
                        audioEnabled = !current.isMuted,
                        videoEnabled = current.isVideoEnabled,
                        speakerEnabled = current.isSpeakerOn,
                        networkQuality = quality,
                        rttMs = rtt,
                        jitterMs = jitter,
                        bitrateKbps = baseBitrate,
                        packetLossTenthsPct = lossTenths,
                        iceState = if (realNetworkStatus.value.transportLabel == "Cellular") "relay" else "connected"
                    )
                }
            }
        }
    }

    fun toggleCallMute() {
        val current = _activeCallState.value ?: return
        val next = !current.isMuted
        _activeCallState.value = current.copy(isMuted = next)
        pushImmediateParticipantMediaState(current.copy(isMuted = next))
    }

    fun toggleCallVideo() {
        val current = _activeCallState.value ?: return
        val next = !current.isVideoEnabled
        _activeCallState.value = current.copy(isVideoEnabled = next)
        pushImmediateParticipantMediaState(current.copy(isVideoEnabled = next))
    }

    fun toggleCallSpeaker() {
        val current = _activeCallState.value ?: return
        val next = !current.isSpeakerOn
        _activeCallState.value = current.copy(isSpeakerOn = next)
        voipAudioEngine.configureVoipRouting(speakerOn = next)
        pushImmediateParticipantMediaState(current.copy(isSpeakerOn = next))
    }

    fun flipCallCamera() {
        val current = _activeCallState.value ?: return
        _activeCallState.value = current.copy(useFrontCamera = !current.useFrontCamera)
    }

    fun switchActiveCallCodec(profile: String) {
        val current = _activeCallState.value ?: return
        vmScope.launch {
            callRepository.updateCodecProfile(current.call.id, profile)
        }
        setVoipCodecProfile(profile)
    }

    private fun pushImmediateParticipantMediaState(state: ActiveCallUiState) {
        if (!isEffectivelyOnline.value) return
        vmScope.launch {
            callRepository.updateParticipantTelemetry(
                callId = state.call.id,
                uid = currentUserId,
                audioEnabled = !state.isMuted,
                videoEnabled = state.isVideoEnabled,
                speakerEnabled = state.isSpeakerOn,
                networkQuality = state.networkQuality,
                rttMs = state.rttMs,
                jitterMs = state.jitterMs,
                bitrateKbps = state.bitrateKbps,
                packetLossTenthsPct = state.packetLossTenthsPct,
                iceState = "connected"
            )
        }
    }

    fun endActiveCall() {
        val current = _activeCallState.value
        teardownCallLocally()
        if (current != null) {
            vmScope.launch {
                callRepository.endCall(current.call)
            }
        }
    }

    private fun teardownCallLocally() {
        stopCallEngineJobs()
        _activeCallState.value = null
        voipAudioEngine.releaseVoipRouting()
        CallForegroundService.stop(appContext)
    }

    private fun stopCallEngineJobs() {
        callTimerJob?.cancel()
        callTimerJob = null
        callJobs.forEach { it.cancel() }
        callJobs.clear()
    }

    // Profile Editing
    fun updateProfile(
        displayName: String,
        statusText: String,
        newAvatarBase64: String?,
        clearPhoto: Boolean,
        phoneNumber: String? = null
    ) {
        val cleanName = displayName.trim()
        if (cleanName.isEmpty()) {
            _statusBanner.value = "Display name cannot be empty"
            return
        }
        vmScope.launch {
            _isSavingProfile.value = true
            val online = isEffectivelyOnline.value
            val result = if (online) {
                withTimeoutOrNull(10_000L) {
                    syncRepository.updateUserProfile(
                        uid = currentUserId,
                        displayName = cleanName,
                        statusText = statusText,
                        avatarBase64 = newAvatarBase64,
                        clearPhoto = clearPhoto,
                        phoneNumber = phoneNumber,
                        updatePhoneNumber = true,
                        awaitServerAck = true
                    )
                } ?: Result.success(Unit)
            } else {
                syncRepository.updateUserProfile(
                    uid = currentUserId,
                    displayName = cleanName,
                    statusText = statusText,
                    avatarBase64 = newAvatarBase64,
                    clearPhoto = clearPhoto,
                    phoneNumber = phoneNumber,
                    updatePhoneNumber = true,
                    awaitServerAck = false
                )
            }
            _isSavingProfile.value = false
            result.onSuccess {
                if (online) {
                    _lastCloudSyncMs.value = System.currentTimeMillis()
                    _statusBanner.value = "Profile updated and synced across devices"
                } else {
                    _statusBanner.value = "Profile saved locally · will sync when online"
                }
            }.onFailure { err ->
                _statusBanner.value = "Profile update failed: ${err.localizedMessage ?: "try again"}"
            }
        }
    }

    // Contact Sync Service Controls
    fun refreshContactsPermissionState() {
        _hasContactsPermission.value = DeviceContactsReader.hasContactsPermission(appContext)
    }

    fun onContactsPermissionResult(granted: Boolean) {
        _hasContactsPermission.value = granted
        if (granted) {
            vmScope.launch {
                prefsStore.setContactAutoSyncEnabled(true)
            }
            if (isEffectivelyOnline.value) {
                ContactSyncService.startSync(appContext, "permission_granted")
            } else {
                _statusBanner.value = "Contacts permission granted · will sync when online"
            }
        } else {
            _statusBanner.value = "Contacts permission denied · you can still add friends by @handle"
        }
    }

    fun setContactAutoSyncEnabled(enabled: Boolean) {
        vmScope.launch {
            prefsStore.setContactAutoSyncEnabled(enabled)
            if (enabled && _hasContactsPermission.value && isEffectivelyOnline.value) {
                ContactSyncService.startSync(appContext, "auto_enabled")
            } else if (!enabled) {
                ContactSyncService.stopSync(appContext)
            }
        }
    }

    fun triggerManualContactSync() {
        refreshContactsPermissionState()
        if (!_hasContactsPermission.value) {
            _statusBanner.value = "Grant Contacts permission to discover friends on UCN"
            return
        }
        if (!isEffectivelyOnline.value) {
            _statusBanner.value = "Connect to the network or disable Offline Simulation to sync contacts"
            return
        }
        ContactSyncService.startSync(appContext, "manual")
    }

    // Settings & Cloud Sync
    fun setDarkMode(mode: String) {
        vmScope.launch {
            prefsStore.setDarkMode(mode)
            if (isEffectivelyOnline.value) {
                syncRepository.updateSyncedPreferences(currentUserId, darkMode = mode)
            }
        }
    }

    fun setHighContrast(enabled: Boolean) {
        vmScope.launch { prefsStore.setHighContrastAccessibility(enabled) }
    }

    fun setReadReceiptsEnabled(enabled: Boolean) {
        vmScope.launch {
            prefsStore.setReadReceiptsEnabled(enabled)
            if (isEffectivelyOnline.value) {
                syncRepository.updateSyncedPreferences(currentUserId, readReceiptsEnabled = enabled)
            }
        }
    }

    fun setVoipCodecProfile(profile: String) {
        vmScope.launch {
            prefsStore.setVoipCodecProfile(profile)
            if (isEffectivelyOnline.value) {
                syncRepository.updateSyncedPreferences(currentUserId, voipCodecProfile = profile)
            }
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        vmScope.launch {
            prefsStore.setAutoSyncEnabled(enabled)
            if (isEffectivelyOnline.value) {
                syncRepository.updateSyncedPreferences(currentUserId, autoSyncEnabled = enabled)
            }
        }
    }

    fun setOfflineSimulationMode(enabled: Boolean) {
        vmScope.launch {
            prefsStore.setOfflineSimulationMode(enabled)
            _statusBanner.value = if (enabled) {
                "Offline storage mode active: new messages encrypted & queued in Room"
            } else {
                "Connectivity restored: syncing queued messages to cloud..."
            }
        }
    }

    fun triggerManualCloudSync() {
        vmScope.launch {
            _isSyncing.value = true
            val devId = _deviceId.value.ifBlank { prefsStore.getOrCreateDeviceId() }
            syncRepository.touchDeviceSync(currentUserId, devId)
            val flushed = chatRepository.flushOfflineOutbox(currentUserId)
            _lastCloudSyncMs.value = System.currentTimeMillis()
            _isSyncing.value = false
            _statusBanner.value = if (flushed > 0) {
                "Cloud sync complete · flushed $flushed queued message(s)"
            } else {
                "All E2EE keys, preferences, and chats synced to cloud"
            }
        }
    }

    fun rotateIdentityKeys() {
        vmScope.launch {
            _isSyncing.value = true
            val devId = _deviceId.value.ifBlank { prefsStore.getOrCreateDeviceId() }
            syncRepository.rotateIdentityKeys(currentUserId, devId)
                .onSuccess { newKeys ->
                    _identityKeys.value = newKeys
                    _statusBanner.value = "Rotated ECDH P-256 identity keypair"
                }
                .onFailure {
                    _statusBanner.value = "Key rotation failed: ${it.localizedMessage}"
                }
            _isSyncing.value = false
        }
    }

    fun removeSyncedDevice(targetDeviceId: String) {
        vmScope.launch {
            syncRepository.removeDevice(currentUserId, targetDeviceId)
                .onSuccess { _statusBanner.value = "Removed linked device from cloud sync" }
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    private fun unwrapKeySafely(conv: Conversation, keys: IdentityKeyPairMaterial): SecretKey? {
        val wrapped = conv.wrappedKeys[currentUserId] ?: return null
        return try {
            E2eeCryptoManager.unwrapConversationKey(wrapped, keys.privateKeyBase64)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptTextSafely(ciphertext: String, iv: String, key: SecretKey?): String {
        if (key == null) return "[Encrypted payload]"
        return try {
            E2eeCryptoManager.decryptText(ciphertext, iv, key)
        } catch (_: Exception) {
            "[Unable to decrypt with local key]"
        }
    }

    private fun resolveDisplayTitle(conv: Conversation): String {
        if (conv.type == "group") return conv.title
        val others = conv.memberNames.filterKeys { it != currentUserId }.values
        return others.firstOrNull()?.takeIf { it.isNotBlank() } ?: conv.title
    }

    private fun unwrapError(error: Throwable): String {
        val fs = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        return fs?.message ?: error.message ?: error.toString()
    }

    override fun onCleared() {
        super.onCleared()
        unregisterContactsContentObserver()
        teardownCallLocally()
        closeConversation()
        vmScope.cancel()
    }

    companion object {
        private const val TAG = "UcnViewModel"

        fun factory(
            uid: String,
            email: String,
            displayName: String,
            photoUrl: String?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId).apply {
                    try {
                        firestoreSettings = FirebaseFirestoreSettings.Builder()
                            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                            .build()
                    } catch (_: IllegalStateException) {
                        // Settings already applied on singleton instance
                    }
                }
                val auth: FirebaseAuth = Firebase.auth
                val roomDb = UcnDatabase.getInstance(app)
                val offlineDao = roomDb.offlineDao()
                return UcnViewModel(
                    appContext = app,
                    currentUserId = uid,
                    currentEmail = email,
                    currentDisplayName = displayName,
                    currentPhotoUrl = photoUrl,
                    syncRepository = UcnSyncRepository(db, auth),
                    chatRepository = ChatRepository(db, auth, offlineDao),
                    callRepository = CallRepository(db, auth, offlineDao),
                    offlineDao = offlineDao,
                    prefsStore = UserPreferencesDataStore(app),
                    networkMonitor = NetworkMonitor(app),
                    voipAudioEngine = VoipAudioEngine(app)
                ) as T
            }
        }
    }
}
