package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_messages",
    indices = [
        Index(value = ["threadAddress"]),
        Index(value = ["timestamp"]),
        Index(value = ["syncStatus"])
    ]
)
data class SmsMessageEntity(
    @PrimaryKey val id: String,
    val threadAddress: String,
    val senderName: String? = null,
    val body: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val isRead: Boolean,
    val simSlot: Int? = null,
    val subscriptionId: Int? = null,
    val simLabel: String? = null,
    val origin: String = ORIGIN_LOCAL_SIM,
    val syncStatus: String = SYNC_LOCAL_ONLY,
    val detectedOtp: String? = null,
    val sourceDeviceId: String? = null,
    val remoteStatus: String? = null
) {
    companion object {
        const val ORIGIN_LOCAL_SIM = "LOCAL_SIM"
        const val ORIGIN_REMOTE_SYNC = "REMOTE_SYNC"
        const val ORIGIN_SENT_VIA_PHONE_A = "SENT_VIA_PHONE_A"

        const val SYNC_LOCAL_ONLY = "LOCAL_ONLY"
        const val SYNC_QUEUED = "QUEUED"
        const val SYNC_SYNCED = "SYNCED"
        const val SYNC_DELIVERED = "DELIVERED"
        const val SYNC_FAILED = "FAILED"
    }
}

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val pairingId: String,
    val peerDeviceId: String,
    val peerDeviceName: String,
    val peerRole: String,
    val sharedKeyEncrypted: String,
    val verificationSas: String,
    val status: String,
    val lastSyncAt: Long? = null,
    val isPeerOnline: Boolean = false
)

@Entity(tableName = "pending_relay_queue")
data class PendingRelayQueueEntity(
    @PrimaryKey val id: String,
    val pairingId: String,
    val targetDeviceId: String,
    val encryptedPayload: String,
    val iv: String,
    val direction: String,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null
)
