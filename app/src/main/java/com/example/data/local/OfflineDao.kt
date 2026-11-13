package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineDao {
    // Conversations cache
    @Query("SELECT * FROM cached_conversations WHERE ownerUserId = :ownerUserId ORDER BY updatedAtMs DESC")
    fun observeCachedConversations(ownerUserId: String): Flow<List<CachedConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(conversations: List<CachedConversationEntity>)

    @Query("DELETE FROM cached_conversations WHERE ownerUserId = :ownerUserId")
    suspend fun clearConversationsForUser(ownerUserId: String)

    // Messages cache
    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND ownerUserId = :ownerUserId ORDER BY clientTimestampMs ASC")
    fun observeCachedMessages(conversationId: String, ownerUserId: String): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<CachedMessageEntity>)

    // Offline outbox queue
    @Query("SELECT * FROM pending_outbox WHERE ownerUserId = :ownerUserId ORDER BY clientTimestampMs ASC")
    fun observeAllPendingOutbox(ownerUserId: String): Flow<List<PendingOutboxMessageEntity>>

    @Query("SELECT * FROM pending_outbox WHERE conversationId = :conversationId AND ownerUserId = :ownerUserId ORDER BY clientTimestampMs ASC")
    fun observeConversationOutbox(conversationId: String, ownerUserId: String): Flow<List<PendingOutboxMessageEntity>>

    @Query("SELECT * FROM pending_outbox WHERE ownerUserId = :ownerUserId AND status IN ('QUEUED', 'FAILED') ORDER BY clientTimestampMs ASC")
    suspend fun getQueuedOutboxOnce(ownerUserId: String): List<PendingOutboxMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueOutboxMessage(item: PendingOutboxMessageEntity)

    @Query("UPDATE pending_outbox SET status = :status, retryCount = :retryCount, errorMessage = :errorMessage WHERE clientMessageId = :clientMessageId")
    suspend fun updateOutboxStatus(clientMessageId: String, status: String, retryCount: Int, errorMessage: String?)

    @Query("DELETE FROM pending_outbox WHERE clientMessageId = :clientMessageId")
    suspend fun removeOutboxMessage(clientMessageId: String)

    // Cached call logs
    @Query("SELECT * FROM cached_call_logs WHERE ownerUserId = :ownerUserId ORDER BY startedAtMs DESC")
    fun observeCachedCallLogs(ownerUserId: String): Flow<List<CachedCallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallLogs(logs: List<CachedCallLogEntity>)
}
