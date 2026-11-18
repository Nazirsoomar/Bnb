package com.example.telephony

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager

/**
 * Mandatory RESPOND_VIA_MESSAGE service required by Android Default SMS app role.
 * Handles quick-reply SMS responses initiated from incoming call screens.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val uri = intent.data
            val destination = uri?.schemeSpecificPart?.substringBefore("?")?.trim().orEmpty()
            val message = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (destination.isNotEmpty() && message.isNotEmpty()) {
                SmsTelephonyHelper.sendSms(
                    context = applicationContext,
                    destinationAddress = destination,
                    body = message,
                    subscriptionId = null,
                    localMessageId = "headless_${System.currentTimeMillis()}"
                )
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
