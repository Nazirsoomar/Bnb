package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {

    const val CHANNEL_SYNC_SERVICE = "dualphone_sync_service"
    const val CHANNEL_REMOTE_SMS_SOUND = "dualphone_remote_sms_sound"
    const val CHANNEL_REMOTE_SMS_SILENT = "dualphone_remote_sms_silent"

    const val EXTRA_THREAD_ADDRESS = "extra_thread_address"
    const val ACTION_COPY_OTP = "com.example.ACTION_COPY_OTP"
    const val EXTRA_OTP_CODE = "extra_otp_code"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC_SERVICE,
            "Background SMS Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps encrypted SMS relay active between paired phones"
            setShowBadge(false)
        }

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val smsSoundChannel = NotificationChannel(
            CHANNEL_REMOTE_SMS_SOUND,
            "Synced SMS & OTP Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Real-time notifications for incoming and synced SMS messages"
            enableVibration(true)
            setSound(soundUri, null)
        }

        val smsSilentChannel = NotificationChannel(
            CHANNEL_REMOTE_SMS_SILENT,
            "Synced SMS (Silent)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Silent notifications for synced SMS messages"
            enableVibration(false)
            setSound(null, null)
        }

        nm.createNotificationChannels(listOf(syncChannel, smsSoundChannel, smsSilentChannel))
    }

    fun buildForegroundServiceNotification(
        context: Context,
        statusText: String
    ) = NotificationCompat.Builder(context, CHANNEL_SYNC_SERVICE)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("DualPhone SMS Sync Active")
        .setContentText(statusText)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                1001,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    fun showIncomingSmsNotification(
        context: Context,
        messageId: String,
        senderDisplay: String,
        threadAddress: String,
        body: String,
        detectedOtp: String?,
        isRemoteSync: Boolean,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureChannels(context)
        val channelId = if (soundEnabled) CHANNEL_REMOTE_SMS_SOUND else CHANNEL_REMOTE_SMS_SILENT

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THREAD_ADDRESS, threadAddress)
        }
        val openPending = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val badgePrefix = if (isRemoteSync) "Synced • " else ""
        val title = if (detectedOtp != null) {
            "${badgePrefix}OTP $detectedOtp from $senderDisplay"
        } else {
            "$badgePrefix$senderDisplay"
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (!vibrationEnabled) {
            builder.setVibrate(longArrayOf(0L))
        }
        if (!soundEnabled) {
            builder.setSilent(true)
        }

        if (detectedOtp != null) {
            val copyIntent = Intent(context, OtpCopyActionReceiver::class.java).apply {
                action = ACTION_COPY_OTP
                putExtra(EXTRA_OTP_CODE, detectedOtp)
            }
            val copyPending = PendingIntent.getBroadcast(
                context,
                (messageId + "_copy").hashCode(),
                copyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_menu_edit,
                "Copy $detectedOtp",
                copyPending
            )
        }

        NotificationManagerCompat.from(context).notify(messageId.hashCode(), builder.build())
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
