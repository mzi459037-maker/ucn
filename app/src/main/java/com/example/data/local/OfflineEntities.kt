package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_conversations")
data class CachedConversationEntity(
    @PrimaryKey val id: String,
    val ownerUserId: String,
    val type: String,
    val title: String,
    val createdBy: String,
    val memberUidsCsv: String,
    val memberNamesJson: String,
    val wrappedKeyForUser: String,
    val keyVersion: Long,
    val lastMessagePreview: String,
    val lastMessageSenderName: String,
    val lastMessageType: String,
    val lastMessageAtMs: Long,
    val activeCallId: String?,
    val updatedAtMs: Long
)

@Entity(tableName = "cached_messages")
data class CachedMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val ownerUserId: String,
    val senderId: String,
    val senderName: String,
    val messageType: String,
    val ciphertext: String,
    val iv: String,
    val mediaCiphertext: String?,
    val mediaIv: String?,
    val mediaMimeType: String?,
    val mediaName: String?,
    val mediaDurationMs: Long?,
    val keyVersion: Long,
    val clientMessageId: String,
    val clientTimestampMs: Long,
    val isSynced: Boolean
)

@Entity(tableName = "pending_outbox")
data class PendingOutboxMessageEntity(
    @PrimaryKey val clientMessageId: String,
    val conversationId: String,
    val ownerUserId: String,
    val senderId: String,
    val senderName: String,
    val memberUidsCsv: String,
    val messageType: String,
    val ciphertext: String,
    val iv: String,
    val lastPreviewCiphertext: String,
    val lastPreviewIv: String,
    val mediaCiphertext: String?,
    val mediaIv: String?,
    val mediaMimeType: String?,
    val mediaName: String?,
    val mediaDurationMs: Long?,
    val keyVersion: Long,
    val clientTimestampMs: Long,
    val status: String, // QUEUED, SENDING, FAILED
    val retryCount: Int = 0,
    val errorMessage: String? = null
)

@Entity(tableName = "cached_call_logs")
data class CachedCallLogEntity(
    @PrimaryKey val callId: String,
    val ownerUserId: String,
    val conversationId: String,
    val conversationTitle: String,
    val initiatorId: String,
    val initiatorName: String,
    val callType: String,
    val isGroup: Boolean,
    val status: String,
    val codecProfile: String,
    val sasCode: String,
    val startedAtMs: Long,
    val endedAtMs: Long?
)
