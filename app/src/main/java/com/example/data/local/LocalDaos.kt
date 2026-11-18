package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ConversationSummary(
    val threadAddress: String,
    val senderName: String?,
    val lastBody: String,
    val lastTimestamp: Long,
    val unreadCount: Int,
    val latestOrigin: String,
    val latestOtp: String?,
    val simLabel: String?
)

@Dao
interface SmsDao {
    @Query("SELECT * FROM sms_messages ORDER BY timestamp DESC")
    fun observeAllMessages(): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_messages WHERE threadAddress = :threadAddress ORDER BY timestamp ASC")
    fun observeThread(threadAddress: String): Flow<List<SmsMessageEntity>>

    @Query(
        """
        SELECT * FROM sms_messages
        WHERE body LIKE '%' || :query || '%'
           OR threadAddress LIKE '%' || :query || '%'
           OR COALESCE(senderName, '') LIKE '%' || :query || '%'
           OR COALESCE(detectedOtp, '') LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        """
    )
    fun searchMessages(query: String): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_messages WHERE detectedOtp IS NOT NULL ORDER BY timestamp DESC LIMIT 10")
    fun observeRecentOtps(): Flow<List<SmsMessageEntity>>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_messages WHERE syncStatus = 'QUEUED'")
    fun observeQueuedMessageCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SmsMessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(messages: List<SmsMessageEntity>)

    @Query("UPDATE sms_messages SET isRead = 1 WHERE threadAddress = :threadAddress")
    suspend fun markThreadAsRead(threadAddress: String)

    @Query("UPDATE sms_messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markMessageAsRead(messageId: String)

    @Query("UPDATE sms_messages SET syncStatus = :syncStatus, remoteStatus = :remoteStatus WHERE id = :messageId")
    suspend fun updateSyncStatus(messageId: String, syncStatus: String, remoteStatus: String?)

    @Query("DELETE FROM sms_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM sms_messages WHERE threadAddress = :threadAddress")
    suspend fun deleteThread(threadAddress: String)

    @Query("DELETE FROM sms_messages")
    suspend fun clearAll()
}

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices WHERE status = 'active' ORDER BY lastSyncAt DESC")
    fun observeActivePairings(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices ORDER BY lastSyncAt DESC")
    fun observeAllPairings(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE pairingId = :pairingId LIMIT 1")
    suspend fun getByPairingId(pairingId: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_devices WHERE status = 'active'")
    suspend fun getActivePairingsOnce(): List<PairedDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairing(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET lastSyncAt = :timestamp, isPeerOnline = :isOnline WHERE pairingId = :pairingId")
    suspend fun updateSyncState(pairingId: String, timestamp: Long, isOnline: Boolean)

    @Query("UPDATE paired_devices SET status = 'revoked' WHERE pairingId = :pairingId")
    suspend fun markRevoked(pairingId: String)

    @Query("DELETE FROM paired_devices WHERE pairingId = :pairingId")
    suspend fun deleteByPairingId(pairingId: String)

    @Query("DELETE FROM paired_devices")
    suspend fun clearAll()
}

@Dao
interface PendingRelayDao {
    @Query("SELECT * FROM pending_relay_queue ORDER BY createdAt ASC")
    suspend fun getPendingAll(): List<PendingRelayQueueEntity>

    @Query("SELECT COUNT(*) FROM pending_relay_queue")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: PendingRelayQueueEntity)

    @Query("DELETE FROM pending_relay_queue WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE pending_relay_queue SET retryCount = retryCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: String, error: String?)

    @Query("DELETE FROM pending_relay_queue")
    suspend fun clearAll()
}
