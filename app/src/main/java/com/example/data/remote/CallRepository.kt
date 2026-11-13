package com.example.data.remote

import com.example.crypto.E2eeCryptoManager
import com.example.data.local.CachedCallLogEntity
import com.example.data.local.OfflineDao
import com.example.data.model.CallParticipant
import com.example.data.model.Conversation
import com.example.data.model.VoipCall
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CallRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val offlineDao: OfflineDao? = null
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in with Google.")

    fun observeCalls(uid: String): Flow<List<VoipCall>> =
        db.collection("calls")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(VoipCall::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.startedAt?.toDate()?.time ?: 0L }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, "calls")
                }
                throw error
            }

    fun observeCall(callId: String): Flow<VoipCall?> =
        db.collection("calls").document(callId)
            .snapshots()
            .map { it.toObject(VoipCall::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser != null) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, "calls/$callId")
                }
                throw error
            }

    fun observeParticipants(callId: String, uid: String): Flow<List<CallParticipant>> =
        db.collection("calls").document(callId).collection("participants")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(CallParticipant::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, "calls/$callId/participants")
                }
                throw error
            }

    suspend fun startCall(
        conversation: Conversation,
        initiatorId: String,
        initiatorName: String,
        callType: String,
        codecProfile: String,
        sdpFingerprint: String
    ): Result<String> {
        return try {
            requireUserId()
            val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(20)}"
            val sasCode = E2eeCryptoManager.generateSasCode(conversation.id, initiatorId)
            val isGroup = conversation.type == "group" || conversation.memberUids.size > 2
            val initialStatus = if (conversation.memberUids.size <= 1) "active" else "ringing"

            db.collection("calls").document(callId).set(
                mapOf(
                    "conversationId" to conversation.id,
                    "conversationTitle" to conversation.title.take(120),
                    "initiatorId" to initiatorId,
                    "initiatorName" to initiatorName.take(100),
                    "callType" to callType,
                    "isGroup" to isGroup,
                    "status" to initialStatus,
                    "memberUids" to conversation.memberUids,
                    "activeParticipantUids" to listOf(initiatorId),
                    "codecProfile" to codecProfile,
                    "sasCode" to sasCode,
                    "startedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("calls").document(callId)
                .collection("participants").document(initiatorId).set(
                    mapOf(
                        "callId" to callId,
                        "userId" to initiatorId,
                        "displayName" to initiatorName.take(100),
                        "memberUids" to conversation.memberUids,
                        "audioEnabled" to true,
                        "videoEnabled" to (callType == "video"),
                        "speakerEnabled" to (callType == "video"),
                        "networkQuality" to "excellent",
                        "rttMs" to 22L,
                        "jitterMs" to 3L,
                        "bitrateKbps" to if (callType == "video") 1450L else 48L,
                        "packetLossTenthsPct" to 0L,
                        "iceState" to "connected",
                        "sdpFingerprint" to sdpFingerprint,
                        "joinedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()

            db.collection("conversations").document(conversation.id).update(
                mapOf(
                    "activeCallId" to callId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "calls")
            Result.failure(e)
        }
    }

    suspend fun joinCall(
        call: VoipCall,
        uid: String,
        displayName: String,
        audioEnabled: Boolean,
        videoEnabled: Boolean,
        speakerEnabled: Boolean,
        sdpFingerprint: String
    ): Result<Unit> {
        return try {
            requireUserId()
            val nextParticipants = (call.activeParticipantUids + uid).distinct().take(50)
            db.collection("calls").document(call.id).update(
                mapOf(
                    "status" to "active",
                    "activeParticipantUids" to nextParticipants,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val partRef = db.collection("calls").document(call.id)
                .collection("participants").document(uid)
            val snap = partRef.get().await()
            if (!snap.exists()) {
                partRef.set(
                    mapOf(
                        "callId" to call.id,
                        "userId" to uid,
                        "displayName" to displayName.take(100),
                        "memberUids" to call.memberUids,
                        "audioEnabled" to audioEnabled,
                        "videoEnabled" to videoEnabled,
                        "speakerEnabled" to speakerEnabled,
                        "networkQuality" to "excellent",
                        "rttMs" to 24L,
                        "jitterMs" to 4L,
                        "bitrateKbps" to if (videoEnabled) 1380L else 48L,
                        "packetLossTenthsPct" to 0L,
                        "iceState" to "connected",
                        "sdpFingerprint" to sdpFingerprint,
                        "joinedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                partRef.update(
                    mapOf(
                        "audioEnabled" to audioEnabled,
                        "videoEnabled" to videoEnabled,
                        "speakerEnabled" to speakerEnabled,
                        "iceState" to "connected",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "calls/${call.id}")
            Result.failure(e)
        }
    }

    suspend fun updateParticipantTelemetry(
        callId: String,
        uid: String,
        audioEnabled: Boolean,
        videoEnabled: Boolean,
        speakerEnabled: Boolean,
        networkQuality: String,
        rttMs: Long,
        jitterMs: Long,
        bitrateKbps: Long,
        packetLossTenthsPct: Long,
        iceState: String
    ): Result<Unit> {
        return try {
            requireUserId()
            db.collection("calls").document(callId)
                .collection("participants").document(uid)
                .update(
                    mapOf(
                        "audioEnabled" to audioEnabled,
                        "videoEnabled" to videoEnabled,
                        "speakerEnabled" to speakerEnabled,
                        "networkQuality" to networkQuality,
                        "rttMs" to rttMs.coerceIn(0L, 60000L),
                        "jitterMs" to jitterMs.coerceIn(0L, 60000L),
                        "bitrateKbps" to bitrateKbps.coerceIn(0L, 50000L),
                        "packetLossTenthsPct" to packetLossTenthsPct.coerceIn(0L, 1000L),
                        "iceState" to iceState,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "calls/$callId/participants/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateCodecProfile(callId: String, codecProfile: String): Result<Unit> {
        return try {
            requireUserId()
            db.collection("calls").document(callId).update(
                mapOf(
                    "codecProfile" to codecProfile,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "calls/$callId")
            Result.failure(e)
        }
    }

    suspend fun endCall(call: VoipCall): Result<Unit> {
        return try {
            requireUserId()
            if (call.status != "ended") {
                db.collection("calls").document(call.id).update(
                    mapOf(
                        "status" to "ended",
                        "activeParticipantUids" to emptyList<String>(),
                        "endedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            if (call.conversationId.isNotBlank()) {
                try {
                    db.collection("conversations").document(call.conversationId).update(
                        mapOf(
                            "activeCallId" to null,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (_: Exception) {
                    // Best-effort clear of activeCallId pointer
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "calls/${call.id}")
            Result.failure(e)
        }
    }

    suspend fun cacheCallLogs(ownerUid: String, calls: List<VoipCall>) {
        val entities = calls.map { c ->
            CachedCallLogEntity(
                callId = c.id,
                ownerUserId = ownerUid,
                conversationId = c.conversationId,
                conversationTitle = c.conversationTitle,
                initiatorId = c.initiatorId,
                initiatorName = c.initiatorName,
                callType = c.callType,
                isGroup = c.isGroup,
                status = c.status,
                codecProfile = c.codecProfile,
                sasCode = c.sasCode,
                startedAtMs = c.startedAt?.toDate()?.time ?: System.currentTimeMillis(),
                endedAtMs = c.endedAt?.toDate()?.time
            )
        }
        offlineDao?.upsertCallLogs(entities)
    }
}
