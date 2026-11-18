package com.example.telephony

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.local.SmsMessageEntity
import com.example.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localMsgId = intent.getStringExtra(SmsTelephonyHelper.EXTRA_LOCAL_MSG_ID) ?: return
        val commandId = intent.getStringExtra(SmsTelephonyHelper.EXTRA_COMMAND_ID)
        val pairingId = intent.getStringExtra(SmsTelephonyHelper.EXTRA_PAIRING_ID)
        val targetDeviceId = intent.getStringExtra(SmsTelephonyHelper.EXTRA_TARGET_DEVICE_ID)
        val pending = goAsync()

        val status = when (intent.action) {
            SmsTelephonyHelper.ACTION_SMS_SENT -> {
                if (resultCode == Activity.RESULT_OK) "SENT" else "FAILED"
            }
            SmsTelephonyHelper.ACTION_SMS_DELIVERED -> {
                if (resultCode == Activity.RESULT_OK) "DELIVERED" else "FAILED"
            }
            else -> null
        }

        val errorDetail = if (resultCode != Activity.RESULT_OK) {
            SmsTelephonyHelper.mapResultCodeToError(resultCode)
        } else {
            null
        }

        if (status == null) {
            pending.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val smsDao = AppDatabase.getInstance(context.applicationContext).smsDao()
                val syncStatus = if (status == "FAILED") {
                    SmsMessageEntity.SYNC_FAILED
                } else {
                    SmsMessageEntity.SYNC_DELIVERED
                }
                smsDao.updateSyncStatus(localMsgId, syncStatus, status)

                if (commandId != null && pairingId != null && targetDeviceId != null) {
                    SyncCoordinator(context.applicationContext).enqueueDeliveryReceipt(
                        pairingId = pairingId,
                        targetDeviceId = targetDeviceId,
                        commandId = commandId,
                        deliveryStatus = status,
                        errorDetail = errorDetail
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
