package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val handle: String = "",
    val photoUrl: String? = null,
    val avatarBase64: String? = null,
    val phoneNumber: String? = null,
    val darkMode: String = "dark",
    val readReceiptsEnabled: Boolean = true,
    val voipCodecProfile: String = "opus_48k_vp9_hd",
    val autoSyncEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserKeyringBackup(
    @DocumentId val id: String = "",
    val userId: String = "",
    val publicKeyBase64: String = "",
    val privateKeyBase64: String = "",
    val keyFingerprint: String = "",
    val keyVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SyncedDevice(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deviceName: String = "",
    val platform: String = "",
    val appVersion: String = "",
    val keyFingerprint: String = "",
    @ServerTimestamp val lastSyncAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ContactEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val peerUserId: String = "",
    val peerDisplayName: String = "",
    val peerHandle: String = "",
    val peerPublicKeyBase64: String = "",
    val peerKeyFingerprint: String = "",
    val verified: Boolean = false,
    val deviceContactName: String? = null,
    val discoverySource: String = "manual",
    val matchedVia: String? = null,
    val peerPhotoUrl: String? = null,
    val peerAvatarBase64: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ContactHashEntry(
    @DocumentId val id: String = "",
    val hashId: String = "",
    val userId: String = "",
    val identifierType: String = "email",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PublicIdentity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val handle: String = "",
    val publicKeyBase64: String = "",
    val keyFingerprint: String = "",
    val photoUrl: String? = null,
    val avatarBase64: String? = null,
    val statusText: String? = null,
    val isOnline: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class HandleDirectory(
    @DocumentId val id: String = "",
    val userId: String = "",
    val handle: String = "",
    val displayName: String = "",
    val publicKeyBase64: String = "",
    val keyFingerprint: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val type: String = "direct",
    val title: String = "",
    val createdBy: String = "",
    val memberUids: List<String> = emptyList(),
    val memberNames: Map<String, String> = emptyMap(),
    val wrappedKeys: Map<String, String> = emptyMap(),
    val keyVersion: Long = 1L,
    val lastMessageCiphertext: String? = null,
    val lastMessageIv: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageType: String? = null,
    val lastMessageAt: Timestamp? = null,
    val activeCallId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class EncryptedMessage(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val messageType: String = "text",
    val ciphertext: String = "",
    val iv: String = "",
    val mediaCiphertext: String? = null,
    val mediaIv: String? = null,
    val mediaMimeType: String? = null,
    val mediaName: String? = null,
    val mediaDurationMs: Long? = null,
    val keyVersion: Long = 1L,
    val clientMessageId: String = "",
    val clientTimestampMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ReadReceipt(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val userId: String = "",
    val displayName: String = "",
    val memberUids: List<String> = emptyList(),
    val lastDeliveredAtMs: Long = 0L,
    val lastReadAtMs: Long = 0L,
    val readReceiptsEnabled: Boolean = true,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class VoipCall(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val conversationTitle: String = "",
    val initiatorId: String = "",
    val initiatorName: String = "",
    val callType: String = "audio",
    val isGroup: Boolean = false,
    val status: String = "ringing",
    val memberUids: List<String> = emptyList(),
    val activeParticipantUids: List<String> = emptyList(),
    val codecProfile: String = "opus_48k_vp9_hd",
    val sasCode: String = "",
    @ServerTimestamp val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CallParticipant(
    @DocumentId val id: String = "",
    val callId: String = "",
    val userId: String = "",
    val displayName: String = "",
    val memberUids: List<String> = emptyList(),
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean = false,
    val speakerEnabled: Boolean = false,
    val networkQuality: String = "excellent",
    val rttMs: Long = 28L,
    val jitterMs: Long = 4L,
    val bitrateKbps: Long = 1280L,
    val packetLossTenthsPct: Long = 0L,
    val iceState: String = "connected",
    val sdpFingerprint: String = "",
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

// --- Domain UI Models ---

enum class DeliveryReceiptState {
    PENDING_OFFLINE,
    FAILED_OFFLINE,
    SENT,
    DELIVERED,
    READ
}

data class DecryptedMessage(
    val id: String,
    val clientMessageId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val messageType: String,
    val text: String,
    val mediaBytes: ByteArray? = null,
    val mediaMimeType: String? = null,
    val mediaName: String? = null,
    val mediaDurationMs: Long? = null,
    val timestampMs: Long,
    val receiptState: DeliveryReceiptState,
    val readByNames: List<String> = emptyList(),
    val isMine: Boolean
)

data class DecryptedConversationSummary(
    val id: String,
    val type: String,
    val title: String,
    val memberUids: List<String>,
    val memberNames: Map<String, String>,
    val lastPreviewText: String,
    val lastMessageSenderName: String,
    val lastMessageType: String,
    val lastActivityMs: Long,
    val activeCallId: String?,
    val isUnread: Boolean = false,
    val keyFingerprint: String = "AES-256-GCM"
)
