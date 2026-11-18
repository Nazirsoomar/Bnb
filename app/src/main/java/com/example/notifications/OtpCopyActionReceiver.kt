package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class OtpCopyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationHelper.ACTION_COPY_OTP) {
            val otp = intent.getStringExtra(NotificationHelper.EXTRA_OTP_CODE) ?: return
            NotificationHelper.copyToClipboard(context, "OTP Code", otp)
            Toast.makeText(context, "Copied OTP: $otp", Toast.LENGTH_SHORT).show()
        }
    }
}
