package com.example.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Mandatory WAP_PUSH_DELIVER receiver required by Android to register as a Default SMS Application.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) {
            // Standard MMS WAP Push hook required for Default SMS Role eligibility
        }
    }
}
