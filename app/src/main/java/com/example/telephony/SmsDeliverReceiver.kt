package com.example.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mandatory Default SMS App receiver for android.provider.Telephony.SMS_DELIVER.
 * Captures incoming SMS without altering original content and relays encrypted copies to paired devices.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        ) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val pendingResult = goAsync()
        val subscriptionId = intent.getIntExtra("subscription", -1).takeIf { it >= 0 }
            ?: intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1).takeIf { it >= 0 }
        val simSlot = intent.getIntExtra("slot", -1).takeIf { it >= 0 }

        // Group multi-part PDUs by originating address while preserving exact body verbatim
        val grouped = linkedMapOf<String, StringBuilder>()
        var timestamp = System.currentTimeMillis()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: msg.originatingAddress ?: "Unknown"
            val builder = grouped.getOrPut(sender) { StringBuilder() }
            builder.append(msg.displayMessageBody ?: msg.messageBody ?: "")
            if (msg.timestampMillis > 0) {
                timestamp = msg.timestampMillis
            }
        }

        val coordinator = SyncCoordinator(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((sender, bodyBuilder) in grouped) {
                    coordinator.handleIncomingSimSms(
                        senderAddress = sender,
                        body = bodyBuilder.toString(),
                        timestamp = timestamp,
                        subscriptionId = subscriptionId,
                        simSlot = simSlot
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
