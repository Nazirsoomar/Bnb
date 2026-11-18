package com.example.telephony

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.example.data.local.SmsMessageEntity
import com.example.domain.OtpExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SimInfo(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val displayName: String,
    val carrierName: String
)

object SmsTelephonyHelper {

    const val ACTION_SMS_SENT = "com.example.telephony.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.example.telephony.SMS_DELIVERED"
    const val EXTRA_LOCAL_MSG_ID = "extra_local_msg_id"
    const val EXTRA_COMMAND_ID = "extra_command_id"
    const val EXTRA_PAIRING_ID = "extra_pairing_id"
    const val EXTRA_TARGET_DEVICE_ID = "extra_target_device_id"

    fun isDefaultSmsApp(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    }

    fun createDefaultSmsRoleIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                null
            }
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    }

    fun hasSmsPermissions(context: Context): Boolean {
        val receive = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
        val send = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
        return receive == PackageManager.PERMISSION_GRANTED &&
            read == PackageManager.PERMISSION_GRANTED &&
            send == PackageManager.PERMISSION_GRANTED
    }

    fun getActiveSims(context: Context): List<SimInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return try {
            val subMgr = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val subs = subMgr.activeSubscriptionInfoList.orEmpty()
            subs.map { info ->
                SimInfo(
                    subscriptionId = info.subscriptionId,
                    simSlotIndex = info.simSlotIndex,
                    displayName = info.displayName?.toString().orEmpty().ifBlank { "SIM ${info.simSlotIndex + 1}" },
                    carrierName = info.carrierName?.toString().orEmpty().ifBlank { "Mobile Network" }
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun resolveSimLabel(context: Context, subscriptionId: Int?): String? {
        if (subscriptionId == null || subscriptionId < 0) return null
        val sims = getActiveSims(context)
        val match = sims.firstOrNull { it.subscriptionId == subscriptionId } ?: return null
        return "SIM ${match.simSlotIndex + 1} • ${match.carrierName}"
    }

    fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importSystemSmsMessages(
        context: Context,
        deviceId: String,
        limit: Int = 250
    ): List<SmsMessageEntity> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }
        val result = mutableListOf<SmsMessageEntity>()
        val sims = getActiveSims(context).associateBy { it.subscriptionId }

        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.SUBSCRIPTION_ID
            )
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val readIdx = cursor.getColumnIndex(Telephony.Sms.READ)
                val subIdx = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                while (cursor.moveToNext() && result.size < limit) {
                    val sysId = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val address = if (addrIdx >= 0) cursor.getString(addrIdx).orEmpty() else ""
                    if (address.isBlank()) continue
                    val body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                    val timestamp = if (dateIdx >= 0) cursor.getLong(dateIdx) else System.currentTimeMillis()
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val isRead = if (readIdx >= 0) cursor.getInt(readIdx) == 1 else true
                    val subId = if (subIdx >= 0) cursor.getInt(subIdx).takeIf { it >= 0 } else null
                    val sim = subId?.let { sims[it] }

                    val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                    result += SmsMessageEntity(
                        id = "sys_sms_$sysId",
                        threadAddress = normalizeAddress(address),
                        senderName = resolveContactName(context, address),
                        body = body,
                        timestamp = timestamp,
                        isIncoming = isIncoming,
                        isRead = isRead,
                        simSlot = sim?.simSlotIndex,
                        subscriptionId = subId,
                        simLabel = sim?.let { "SIM ${it.simSlotIndex + 1} • ${it.carrierName}" },
                        origin = SmsMessageEntity.ORIGIN_LOCAL_SIM,
                        syncStatus = SmsMessageEntity.SYNC_LOCAL_ONLY,
                        detectedOtp = if (isIncoming) OtpExtractor.extractOtp(body) else null,
                        sourceDeviceId = deviceId
                    )
                }
            }
        } catch (_: Exception) {
            // Safely ignore if telephony provider unavailable
        }
        result
    }

    fun writeIncomingSmsToProvider(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?
    ) {
        if (!isDefaultSmsApp(context)) return
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                if (subscriptionId != null && subscriptionId >= 0) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (_: Exception) {
        }
    }

    fun writeSentSmsToProvider(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?
    ) {
        if (!isDefaultSmsApp(context)) return
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, 1)
                if (subscriptionId != null && subscriptionId >= 0) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        } catch (_: Exception) {
        }
    }

    fun deleteSystemSmsIfPresent(context: Context, entityId: String) {
        if (!isDefaultSmsApp(context) || !entityId.startsWith("sys_sms_")) return
        val sysId = entityId.removePrefix("sys_sms_").toLongOrNull() ?: return
        try {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} = ?",
                arrayOf(sysId.toString())
            )
        } catch (_: Exception) {
        }
    }

    fun sendSms(
        context: Context,
        destinationAddress: String,
        body: String,
        subscriptionId: Int?,
        localMessageId: String,
        remoteCommandId: String? = null,
        pairingId: String? = null,
        targetDeviceId: String? = null
    ): Result<Unit> = runCatching {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
        ) { "SEND_SMS permission is not granted on this device." }

        val smsManager: SmsManager = getSmsManager(context, subscriptionId)
        val parts = smsManager.divideMessage(body)

        val sentIntent = PendingIntent.getBroadcast(
            context,
            (localMessageId + "_sent").hashCode(),
            Intent(context, SmsStatusReceiver::class.java).apply {
                action = ACTION_SMS_SENT
                putExtra(EXTRA_LOCAL_MSG_ID, localMessageId)
                if (remoteCommandId != null) putExtra(EXTRA_COMMAND_ID, remoteCommandId)
                if (pairingId != null) putExtra(EXTRA_PAIRING_ID, pairingId)
                if (targetDeviceId != null) putExtra(EXTRA_TARGET_DEVICE_ID, targetDeviceId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val deliveredIntent = PendingIntent.getBroadcast(
            context,
            (localMessageId + "_deliv").hashCode(),
            Intent(context, SmsStatusReceiver::class.java).apply {
                action = ACTION_SMS_DELIVERED
                putExtra(EXTRA_LOCAL_MSG_ID, localMessageId)
                if (remoteCommandId != null) putExtra(EXTRA_COMMAND_ID, remoteCommandId)
                if (pairingId != null) putExtra(EXTRA_PAIRING_ID, pairingId)
                if (targetDeviceId != null) putExtra(EXTRA_TARGET_DEVICE_ID, targetDeviceId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (parts.size <= 1) {
            smsManager.sendTextMessage(destinationAddress, null, body, sentIntent, deliveredIntent)
        } else {
            val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { add(sentIntent) }
            }
            val deliveredIntents = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { add(deliveredIntent) }
            }
            smsManager.sendMultipartTextMessage(
                destinationAddress,
                null,
                parts,
                sentIntents,
                deliveredIntents
            )
        }

        writeSentSmsToProvider(context, destinationAddress, body, System.currentTimeMillis(), subscriptionId)
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(context: Context, subscriptionId: Int?): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null && subscriptionId >= 0) {
                base.createForSubscriptionId(subscriptionId)
            } else {
                base
            }
        } else {
            if (subscriptionId != null && subscriptionId >= 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    }

    fun normalizeAddress(raw: String): String {
        val trimmed = raw.trim()
        // Keep alphanumeric sender IDs (banks/services) intact; normalize phone digits
        return if (trimmed.any { it.isLetter() }) {
            trimmed
        } else {
            trimmed.replace(" ", "").replace("-", "")
        }
    }

    fun mapResultCodeToError(resultCode: Int): String? = when (resultCode) {
        Activity.RESULT_OK -> null
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Carrier generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Cellular radio is off"
        else -> "SMS send failed (code $resultCode)"
    }
}
