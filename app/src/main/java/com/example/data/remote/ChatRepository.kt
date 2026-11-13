package com.example.data.remote

import android.util.Log
import com.example.crypto.E2eeCryptoManager
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.local.CachedConversationEntity
import com.example.data.local.CachedMessageEntity
import com.example.data.local.OfflineDao
import com.example.data.local.PendingOutboxMessageEntity
import com.example.data.model.Conversation
import com.example.data.model.EncryptedMessage
import com.example.data.model.PublicIdentity
import com.example.data.model.ReadReceipt
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
import javax.crypto.SecretKey

sealed interface SendMessageOutcome {
    data class SentOnline(val messageId: String) : SendMessageOutcome
    data class QueuedOffline(val clientMessageId: String) : SendMessageOutcome
}

class ChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val offlineDao: OfflineDao? = null
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in with Google.")

    fun observeConversations(uid: String): Flow<List<Conversation>> =
        db.collection("conversations")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, "conversations")
                }
                throw error
            }

    fun observeConversation(conversationId: String): Flow<Conversation?> =
        db.collection("conversations").document(conversationId)
            .snapshots()
            .map { it.toObject(Conversation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser != null) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, "conversations/$conversationId")
                }
                throw error
            }

    fun observeMessages(conversationId: String, uid: String): Flow<List<EncryptedMessage>> =
        db.collection("conversations").document(conversationId).collection("messages")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(EncryptedMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.clientTimestampMs }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(
                        firestoreError ?: error,
                        OperationType.LIST,
                        "conversations/$conversationId/messages"
                    )
                }
                throw error
            }

    fun observeReadReceipts(conversationId: String, uid: String): Flow<List<ReadReceipt>> =
        db.collection("conversations").document(conversationId).collection("readReceipts")
            .whereArrayContains("memberUids", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(ReadReceipt::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(
                        firestoreError ?: error,
                        OperationType.LIST,
                        "conversations/$conversationId/readReceipts"
                    )
                }
                throw error
            }

    suspend fun createDirectOrVaultConversation(
        currentUid: String,
        currentName: String,
        myKeys: IdentityKeyPairMaterial,
        peer: PublicIdentity?,
        customTitle: String? = null
    ): Result<String> {
        return try {
            requireUserId()
            val isSelfVault = peer == null || peer.userId == currentUid
            val convId = if (isSelfVault) {
                "vault_$currentUid"
            } else {
                val sorted = listOf(currentUid, peer!!.userId).sorted()
                "dm_${sorted[0]}_${sorted[1]}"
            }
            val docRef = db.collection("conversations").document(convId)
            val existing = try {
                docRef.get().await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null else throw e
            }
            if (existing != null && existing.exists()) {
                return Result.success(convId)
            }

            val sessionKey = E2eeCryptoManager.generateConversationKey()
            val wrappedKeys = mutableMapOf<String, String>()
            wrappedKeys[currentUid] = E2eeCryptoManager.wrapConversationKeyForMember(
                conversationKey = sessionKey,
                senderPrivateKeyBase64 = myKeys.privateKeyBase64,
                recipientPublicKeyBase64 = myKeys.publicKeyBase64,
                senderPublicKeyBase64 = myKeys.publicKeyBase64
            )
            val memberUids = mutableListOf(currentUid)
            val memberNames = mutableMapOf(currentUid to currentName.take(100))

            if (!isSelfVault) {
                memberUids.add(peer!!.userId)
                memberNames[peer.userId] = peer.displayName.take(100)
                wrappedKeys[peer.userId] = E2eeCryptoManager.wrapConversationKeyForMember(
                    conversationKey = sessionKey,
                    senderPrivateKeyBase64 = myKeys.privateKeyBase64,
                    recipientPublicKeyBase64 = peer.publicKeyBase64,
                    senderPublicKeyBase64 = myKeys.publicKeyBase64
                )
            }

            val title = customTitle?.take(120)?.ifBlank { null }
                ?: if (isSelfVault) "E2EE Note Vault" else peer!!.displayName.take(120)

            val payload = mapOf(
                "type" to "direct",
                "title" to title,
                "createdBy" to currentUid,
                "memberUids" to memberUids,
                "memberNames" to memberNames,
                "wrappedKeys" to wrappedKeys,
                "keyVersion" to 1L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "conversations")
            Result.failure(e)
        }
    }

    suspend fun createGroupConversation(
        currentUid: String,
        currentName: String,
        myKeys: IdentityKeyPairMaterial,
        title: String,
        peers: List<PublicIdentity>
    ): Result<String> {
        return try {
            requireUserId()
            val convId = "grp_${UUID.randomUUID().toString().replace("-", "").take(20)}"
            val docRef = db.collection("conversations").document(convId)
            val sessionKey = E2eeCryptoManager.generateConversationKey()

            val memberUids = mutableListOf(currentUid)
            val memberNames = mutableMapOf(currentUid to currentName.take(100))
            val wrappedKeys = mutableMapOf<String, String>()
            wrappedKeys[currentUid] = E2eeCryptoManager.wrapConversationKeyForMember(
                conversationKey = sessionKey,
                senderPrivateKeyBase64 = myKeys.privateKeyBase64,
                recipientPublicKeyBase64 = myKeys.publicKeyBase64,
                senderPublicKeyBase64 = myKeys.publicKeyBase64
            )

            peers.distinctBy { it.userId }.filter { it.userId != currentUid }.take(49).forEach { peer ->
                memberUids.add(peer.userId)
                memberNames[peer.userId] = peer.displayName.take(100)
                wrappedKeys[peer.userId] = E2eeCryptoManager.wrapConversationKeyForMember(
                    conversationKey = sessionKey,
                    senderPrivateKeyBase64 = myKeys.privateKeyBase64,
                    recipientPublicKeyBase64 = peer.publicKeyBase64,
                    senderPublicKeyBase64 = myKeys.publicKeyBase64
                )
            }

            val payload = mapOf(
                "type" to "group",
                "title" to title.trim().ifBlank { "Secure Group" }.take(120),
                "createdBy" to currentUid,
                "memberUids" to memberUids,
                "memberNames" to memberNames,
                "wrappedKeys" to wrappedKeys,
                "keyVersion" to 1L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "conversations")
            Result.failure(e)
        }
    }

    suspend fun addMemberToGroup(
        conversation: Conversation,
        currentUid: String,
        myKeys: IdentityKeyPairMaterial,
        newPeer: PublicIdentity
    ): Result<Unit> {
        return try {
            requireUserId()
            if (conversation.type != "group") {
                return Result.failure(IllegalArgumentException("Can only add members to group chats"))
            }
            if (newPeer.userId in conversation.memberUids) {
                return Result.success(Unit)
            }
            val myWrapped = conversation.wrappedKeys[currentUid]
                ?: return Result.failure(IllegalStateException("Missing wrapped session key"))
            val sessionKey = E2eeCryptoManager.unwrapConversationKey(myWrapped, myKeys.privateKeyBase64)
            val newWrapped = E2eeCryptoManager.wrapConversationKeyForMember(
                conversationKey = sessionKey,
                senderPrivateKeyBase64 = myKeys.privateKeyBase64,
                recipientPublicKeyBase64 = newPeer.publicKeyBase64,
                senderPublicKeyBase64 = myKeys.publicKeyBase64
            )

            val nextUids = (conversation.memberUids + newPeer.userId).distinct().take(50)
            val nextNames = conversation.memberNames.toMutableMap().apply {
                this[newPeer.userId] = newPeer.displayName.take(100)
            }
            val nextWrapped = conversation.wrappedKeys.toMutableMap().apply {
                this[newPeer.userId] = newWrapped
            }

            db.collection("conversations").document(conversation.id).update(
                mapOf(
                    "memberUids" to nextUids,
                    "memberNames" to nextNames,
                    "wrappedKeys" to nextWrapped,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "conversations/${conversation.id}")
            Result.failure(e)
        }
    }

    suspend fun sendEncryptedMessage(
        conversation: Conversation,
        senderId: String,
        senderName: String,
        sessionKey: SecretKey,
        messageType: String,
        plaintext: String,
        mediaBytes: ByteArray? = null,
        mediaMimeType: String? = null,
        mediaName: String? = null,
        mediaDurationMs: Long? = null,
        isOnline: Boolean = true
    ): Result<SendMessageOutcome> {
        val clientMessageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val nowMs = System.currentTimeMillis()
        val encryptedText = E2eeCryptoManager.encryptText(plaintext, sessionKey)
        val previewLabel = when (messageType) {
            "image" -> "Encrypted Photo"
            "audio" -> "Encrypted Voice Note"
            "file" -> "Encrypted File"
            "call_event" -> plaintext
            else -> plaintext
        }.take(140)
        val encryptedPreview = E2eeCryptoManager.encryptText(previewLabel, sessionKey)

        val encryptedMedia = mediaBytes?.let { bytes ->
            E2eeCryptoManager.encryptBytes(bytes, sessionKey)
        }

        if (!isOnline) {
            if (offlineDao == null) {
                return Result.failure(IllegalStateException("Offline queue unavailable"))
            }
            offlineDao.enqueueOutboxMessage(
                PendingOutboxMessageEntity(
                    clientMessageId = clientMessageId,
                    conversationId = conversation.id,
                    ownerUserId = senderId,
                    senderId = senderId,
                    senderName = senderName.take(100),
                    memberUidsCsv = conversation.memberUids.joinToString(","),
                    messageType = messageType,
                    ciphertext = encryptedText.ciphertextBase64,
                    iv = encryptedText.ivBase64,
                    lastPreviewCiphertext = encryptedPreview.ciphertextBase64,
                    lastPreviewIv = encryptedPreview.ivBase64,
                    mediaCiphertext = encryptedMedia?.ciphertextBase64,
                    mediaIv = encryptedMedia?.ivBase64,
                    mediaMimeType = mediaMimeType,
                    mediaName = mediaName,
                    mediaDurationMs = mediaDurationMs,
                    keyVersion = conversation.keyVersion,
                    clientTimestampMs = nowMs,
                    status = "QUEUED"
                )
            )
            return Result.success(SendMessageOutcome.QueuedOffline(clientMessageId))
        }

        return pushEncryptedPayloadToFirestore(
            conversationId = conversation.id,
            senderId = senderId,
            senderName = senderName,
            memberUids = conversation.memberUids,
            messageType = messageType,
            ciphertext = encryptedText.ciphertextBase64,
            iv = encryptedText.ivBase64,
            previewCiphertext = encryptedPreview.ciphertextBase64,
            previewIv = encryptedPreview.ivBase64,
            mediaCiphertext = encryptedMedia?.ciphertextBase64,
            mediaIv = encryptedMedia?.ivBase64,
            mediaMimeType = mediaMimeType,
            mediaName = mediaName,
            mediaDurationMs = mediaDurationMs,
            keyVersion = conversation.keyVersion,
            clientMessageId = clientMessageId,
            clientTimestampMs = nowMs
        ).fold(
            onSuccess = { Result.success(SendMessageOutcome.SentOnline(it)) },
            onFailure = { error ->
                if (offlineDao != null && error !is FirebaseFirestoreException) {
                    offlineDao.enqueueOutboxMessage(
                        PendingOutboxMessageEntity(
                            clientMessageId = clientMessageId,
                            conversationId = conversation.id,
                            ownerUserId = senderId,
                            senderId = senderId,
                            senderName = senderName.take(100),
                            memberUidsCsv = conversation.memberUids.joinToString(","),
                            messageType = messageType,
                            ciphertext = encryptedText.ciphertextBase64,
                            iv = encryptedText.ivBase64,
                            lastPreviewCiphertext = encryptedPreview.ciphertextBase64,
                            lastPreviewIv = encryptedPreview.ivBase64,
                            mediaCiphertext = encryptedMedia?.ciphertextBase64,
                            mediaIv = encryptedMedia?.ivBase64,
                            mediaMimeType = mediaMimeType,
                            mediaName = mediaName,
                            mediaDurationMs = mediaDurationMs,
                            keyVersion = conversation.keyVersion,
                            clientTimestampMs = nowMs,
                            status = "QUEUED",
                            errorMessage = error.localizedMessage
                        )
                    )
                    Result.success(SendMessageOutcome.QueuedOffline(clientMessageId))
                } else {
                    Result.failure(error)
                }
            }
        )
    }

    private suspend fun pushEncryptedPayloadToFirestore(
        conversationId: String,
        senderId: String,
        senderName: String,
        memberUids: List<String>,
        messageType: String,
        ciphertext: String,
        iv: String,
        previewCiphertext: String,
        previewIv: String,
        mediaCiphertext: String?,
        mediaIv: String?,
        mediaMimeType: String?,
        mediaName: String?,
        mediaDurationMs: Long?,
        keyVersion: Long,
        clientMessageId: String,
        clientTimestampMs: Long
    ): Result<String> {
        return try {
            requireUserId()
            val msgRef = db.collection("conversations").document(conversationId)
                .collection("messages").document(clientMessageId)

            val msgPayload = mutableMapOf<String, Any>(
                "conversationId" to conversationId,
                "senderId" to senderId,
                "senderName" to senderName.take(100),
                "memberUids" to memberUids,
                "messageType" to messageType,
                "ciphertext" to ciphertext,
                "iv" to iv,
                "keyVersion" to keyVersion,
                "clientMessageId" to clientMessageId,
                "clientTimestampMs" to clientTimestampMs,
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (mediaCiphertext != null && mediaIv != null) {
                msgPayload["mediaCiphertext"] = mediaCiphertext
                msgPayload["mediaIv"] = mediaIv
            }
            if (mediaMimeType != null) msgPayload["mediaMimeType"] = mediaMimeType.take(100)
            if (mediaName != null) msgPayload["mediaName"] = mediaName.take(200)
            if (mediaDurationMs != null) msgPayload["mediaDurationMs"] = mediaDurationMs

            msgRef.set(msgPayload).await()

            val convRef = db.collection("conversations").document(conversationId)
            convRef.update(
                mapOf(
                    "lastMessageCiphertext" to previewCiphertext.take(2048),
                    "lastMessageIv" to previewIv.take(64),
                    "lastMessageSenderId" to senderId,
                    "lastMessageSenderName" to senderName.take(100),
                    "lastMessageType" to messageType,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(clientMessageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "conversations/$conversationId/messages")
            Result.failure(e)
        }
    }

    suspend fun flushOfflineOutbox(uid: String): Int {
        val dao = offlineDao ?: return 0
        val pending = dao.getQueuedOutboxOnce(uid)
        var sentCount = 0
        for (item in pending) {
            dao.updateOutboxStatus(item.clientMessageId, "SENDING", item.retryCount, null)
            val members = item.memberUidsCsv.split(",").filter { it.isNotBlank() }
            val result = pushEncryptedPayloadToFirestore(
                conversationId = item.conversationId,
                senderId = item.senderId,
                senderName = item.senderName,
                memberUids = members,
                messageType = item.messageType,
                ciphertext = item.ciphertext,
                iv = item.iv,
                previewCiphertext = item.lastPreviewCiphertext,
                previewIv = item.lastPreviewIv,
                mediaCiphertext = item.mediaCiphertext,
                mediaIv = item.mediaIv,
                mediaMimeType = item.mediaMimeType,
                mediaName = item.mediaName,
                mediaDurationMs = item.mediaDurationMs,
                keyVersion = item.keyVersion,
                clientMessageId = item.clientMessageId,
                clientTimestampMs = item.clientTimestampMs
            )
            if (result.isSuccess) {
                dao.removeOutboxMessage(item.clientMessageId)
                sentCount++
            } else {
                val err = result.exceptionOrNull()?.localizedMessage ?: "Sync retry pending"
                Log.w("ChatRepository", "Outbox flush failed for ${item.clientMessageId}: $err")
                dao.updateOutboxStatus(item.clientMessageId, "FAILED", item.retryCount + 1, err)
            }
        }
        return sentCount
    }

    suspend fun updateReadReceipt(
        conversationId: String,
        uid: String,
        displayName: String,
        memberUids: List<String>,
        lastDeliveredAtMs: Long,
        lastReadAtMs: Long,
        readReceiptsEnabled: Boolean
    ): Result<Unit> {
        return try {
            requireUserId()
            val receiptRef = db.collection("conversations").document(conversationId)
                .collection("readReceipts").document(uid)
            val existing = receiptRef.get().await()
            val effectiveReadMs = if (readReceiptsEnabled) lastReadAtMs else 0L
            if (!existing.exists()) {
                receiptRef.set(
                    mapOf(
                        "conversationId" to conversationId,
                        "userId" to uid,
                        "displayName" to displayName.take(100),
                        "memberUids" to memberUids,
                        "lastDeliveredAtMs" to lastDeliveredAtMs,
                        "lastReadAtMs" to effectiveReadMs,
                        "readReceiptsEnabled" to readReceiptsEnabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val prevDelivered = existing.getLong("lastDeliveredAtMs") ?: 0L
                val prevRead = existing.getLong("lastReadAtMs") ?: 0L
                receiptRef.update(
                    mapOf(
                        "displayName" to displayName.take(100),
                        "memberUids" to memberUids,
                        "lastDeliveredAtMs" to maxOf(prevDelivered, lastDeliveredAtMs),
                        "lastReadAtMs" to if (readReceiptsEnabled) maxOf(prevRead, lastReadAtMs) else prevRead,
                        "readReceiptsEnabled" to readReceiptsEnabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "conversations/$conversationId/readReceipts/$uid")
            Result.failure(e)
        }
    }

    suspend fun cacheConversationsLocally(ownerUid: String, entities: List<CachedConversationEntity>) {
        offlineDao?.upsertConversations(entities)
    }

    suspend fun cacheMessagesLocally(entities: List<CachedMessageEntity>) {
        if (entities.isNotEmpty()) {
            offlineDao?.upsertMessages(entities)
        }
    }
}
