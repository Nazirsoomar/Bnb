package com.example.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import org.json.JSONObject

@IgnoreExtraProperties
data class DeviceRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deviceName: String = "",
    val role: String = "primary_sim",
    val publicKey: String = "",
    val isOnline: Boolean = true,
    val batteryLevel: Long = 100L,
    val simCarrier: String = "",
    val pendingCount: Long = 0L,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PairingSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val primaryDeviceId: String = "",
    val primaryDeviceName: String = "",
    val primaryPublicKey: String = "",
    val remoteDeviceId: String = "",
    val remoteDeviceName: String = "",
    val remotePublicKey: String = "",
    val pairingCode: String = "",
    val verificationSas: String = "",
    val primaryConfirmed: Boolean = false,
    val remoteConfirmed: Boolean = false,
    val status: String = STATUS_PENDING,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_AWAITING_CONFIRMATION = "awaiting_confirmation"
        const val STATUS_ACTIVE = "active"
        const val STATUS_REVOKED = "revoked"
    }
}

@IgnoreExtraProperties
data class RelayEnvelope(
    @DocumentId val id: String = "",
    val userId: String = "",
    val pairingId: String = "",
    val sourceDeviceId: String = "",
    val targetDeviceId: String = "",
    val encryptedPayload: String = "",
    val iv: String = "",
    val direction: String = DIRECTION_INBOUND_SMS,
    val status: String = STATUS_PENDING,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val DIRECTION_INBOUND_SMS = "inbound_sms"
        const val DIRECTION_OUTBOUND_COMMAND = "outbound_command"
        const val DIRECTION_DELIVERY_RECEIPT = "delivery_receipt"

        const val STATUS_PENDING = "pending"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Plaintext structure serialized to JSON and AES-GCM encrypted BEFORE transmission.
 * Never written unencrypted to Firestore.
 */
data class DecryptedSmsPayload(
    val messageId: String,
    val senderAddress: String,
    val senderName: String?,
    val body: String,
    val originalTimestamp: Long,
    val simSlot: Int?,
    val subscriptionId: Int?,
    val simLabel: String?,
    val isIncoming: Boolean
) {
    fun toJson(): String = JSONObject().apply {
        put("messageId", messageId)
        put("senderAddress", senderAddress)
        put("senderName", senderName ?: "")
        put("body", body)
        put("originalTimestamp", originalTimestamp)
        put("simSlot", simSlot ?: -1)
        put("subscriptionId", subscriptionId ?: -1)
        put("simLabel", simLabel ?: "")
        put("isIncoming", isIncoming)
    }.toString()

    companion object {
        fun fromJson(json: String): DecryptedSmsPayload {
            val obj = JSONObject(json)
            val simSlotRaw = obj.optInt("simSlot", -1)
            val subIdRaw = obj.optInt("subscriptionId", -1)
            return DecryptedSmsPayload(
                messageId = obj.getString("messageId"),
                senderAddress = obj.getString("senderAddress"),
                senderName = obj.optString("senderName").takeIf { it.isNotBlank() },
                body = obj.getString("body"),
                originalTimestamp = obj.getLong("originalTimestamp"),
                simSlot = simSlotRaw.takeIf { it >= 0 },
                subscriptionId = subIdRaw.takeIf { it >= 0 },
                simLabel = obj.optString("simLabel").takeIf { it.isNotBlank() },
                isIncoming = obj.optBoolean("isIncoming", true)
            )
        }
    }
}

data class DecryptedOutboundCommand(
    val commandId: String,
    val recipientAddress: String,
    val body: String,
    val requestedAt: Long,
    val subscriptionId: Int? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("commandId", commandId)
        put("recipientAddress", recipientAddress)
        put("body", body)
        put("requestedAt", requestedAt)
        put("subscriptionId", subscriptionId ?: -1)
    }.toString()

    companion object {
        fun fromJson(json: String): DecryptedOutboundCommand {
            val obj = JSONObject(json)
            val subId = obj.optInt("subscriptionId", -1)
            return DecryptedOutboundCommand(
                commandId = obj.getString("commandId"),
                recipientAddress = obj.getString("recipientAddress"),
                body = obj.getString("body"),
                requestedAt = obj.getLong("requestedAt"),
                subscriptionId = subId.takeIf { it >= 0 }
            )
        }
    }
}

data class DecryptedDeliveryReceipt(
    val commandId: String,
    val deliveryStatus: String,
    val errorDetail: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("commandId", commandId)
        put("deliveryStatus", deliveryStatus)
        put("errorDetail", errorDetail ?: "")
        put("timestamp", timestamp)
    }.toString()

    companion object {
        fun fromJson(json: String): DecryptedDeliveryReceipt {
            val obj = JSONObject(json)
            return DecryptedDeliveryReceipt(
                commandId = obj.getString("commandId"),
                deliveryStatus = obj.getString("deliveryStatus"),
                errorDetail = obj.optString("errorDetail").takeIf { it.isNotBlank() },
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}
