package com.example.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.PendingRelayQueueEntity
import com.example.data.local.SmsMessageEntity
import com.example.data.preferences.AppPreferences
import com.example.data.preferences.DeviceRole
import com.example.data.remote.DecryptedDeliveryReceipt
import com.example.data.remote.DecryptedOutboundCommand
import com.example.data.remote.DecryptedSmsPayload
import com.example.data.remote.RelayEnvelope
import com.example.data.remote.RelayRepository
import com.example.domain.OtpExtractor
import com.example.notifications.NotificationHelper
import com.example.telephony.SmsTelephonyHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncCoordinator(private val context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val smsDao = db.smsDao()
    private val pairedDao = db.pairedDeviceDao()
    private val pendingDao = db.pendingRelayDao()
    private val prefs = AppPreferences(appContext)
    private val crypto = CryptoManager(appContext)
    private val relayRepo = RelayRepository(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isNetworkConnected(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun publishPresence(isOnline: Boolean = true) = withContext(Dispatchers.IO) {
        if (Firebase.auth.currentUser == null) return@withContext
        val settings = prefs.settingsFlow.first()
        val deviceId = prefs.getOrCreateDeviceId()
        val role = when (settings.deviceRole) {
            DeviceRole.PRIMARY_SIM -> "primary_sim"
            DeviceRole.REMOTE_VIEWER -> "remote_viewer"
            DeviceRole.UNSET -> if (SmsTelephonyHelper.isDefaultSmsApp(appContext)) "primary_sim" else "remote_viewer"
        }
        val carrier = SmsTelephonyHelper.getActiveSims(appContext)
            .joinToString(" / ") { it.carrierName }
            .ifBlank { if (role == "primary_sim") "SIM Ready" else "Wi-Fi Only" }
        val pendingCount = pendingDao.getPendingAll().size
        val battery = readBatteryPercentage()

        relayRepo.upsertDevicePresence(
            deviceId = deviceId,
            deviceName = settings.deviceName,
            role = role,
            publicKey = crypto.getPublicKeyBase64(),
            isOnline = isOnline && isNetworkConnected(),
            batteryLevel = battery,
            simCarrier = carrier,
            pendingCount = pendingCount
        )
    }

    suspend fun handleIncomingSimSms(
        senderAddress: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?,
        simSlot: Int?
    ) = withContext(Dispatchers.IO) {
        val settings = prefs.settingsFlow.first()
        val myDeviceId = prefs.getOrCreateDeviceId()
        val normalized = SmsTelephonyHelper.normalizeAddress(senderAddress)
        val senderName = SmsTelephonyHelper.resolveContactName(appContext, senderAddress)
        val simLabel = SmsTelephonyHelper.resolveSimLabel(appContext, subscriptionId)
        val detectedOtp = OtpExtractor.extractOtp(body)
        val messageId = "sms_" + UUID.randomUUID().toString().replace("-", "").take(16)

        SmsTelephonyHelper.writeIncomingSmsToProvider(
            appContext,
            senderAddress,
            body,
            timestamp,
            subscriptionId
        )

        val activePairings = pairedDao.getActivePairingsOnce()
        val shouldSync = settings.autoSyncEnabled && activePairings.isNotEmpty()

        val entity = SmsMessageEntity(
            id = messageId,
            threadAddress = normalized,
            senderName = senderName,
            body = body,
            timestamp = timestamp,
            isIncoming = true,
            isRead = false,
            simSlot = simSlot,
            subscriptionId = subscriptionId,
            simLabel = simLabel,
            origin = SmsMessageEntity.ORIGIN_LOCAL_SIM,
            syncStatus = if (shouldSync) SmsMessageEntity.SYNC_QUEUED else SmsMessageEntity.SYNC_LOCAL_ONLY,
            detectedOtp = detectedOtp,
            sourceDeviceId = myDeviceId
        )
        smsDao.insertMessage(entity)

        NotificationHelper.showIncomingSmsNotification(
            context = appContext,
            messageId = messageId,
            senderDisplay = senderName ?: senderAddress,
            threadAddress = normalized,
            body = body,
            detectedOtp = detectedOtp,
            isRemoteSync = false,
            soundEnabled = settings.notificationSound,
            vibrationEnabled = settings.notificationVibration
        )

        if (!shouldSync) return@withContext

        val payloadJson = DecryptedSmsPayload(
            messageId = messageId,
            senderAddress = senderAddress,
            senderName = senderName,
            body = body,
            originalTimestamp = timestamp,
            simSlot = simSlot,
            subscriptionId = subscriptionId,
            simLabel = simLabel,
            isIncoming = true
        ).toJson()

        for (pairing in activePairings) {
            enqueueAndDispatchEnvelope(
                envelopeId = "${messageId}_${pairing.peerDeviceId.take(6)}",
                localSmsId = messageId,
                pairing = pairing,
                plaintextJson = payloadJson,
                direction = RelayEnvelope.DIRECTION_INBOUND_SMS
            )
        }
    }

    suspend fun sendOutboundSmsCommandFromPhoneB(
        recipientAddress: String,
        body: String,
        pairing: PairedDeviceEntity,
        subscriptionId: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val myDeviceId = prefs.getOrCreateDeviceId()
        val commandId = "cmd_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val normalized = SmsTelephonyHelper.normalizeAddress(recipientAddress)

        val localEntity = SmsMessageEntity(
            id = commandId,
            threadAddress = normalized,
            senderName = SmsTelephonyHelper.resolveContactName(appContext, recipientAddress),
            body = body,
            timestamp = now,
            isIncoming = false,
            isRead = true,
            origin = SmsMessageEntity.ORIGIN_SENT_VIA_PHONE_A,
            syncStatus = SmsMessageEntity.SYNC_QUEUED,
            sourceDeviceId = myDeviceId,
            remoteStatus = "PENDING"
        )
        smsDao.insertMessage(localEntity)

        val cmdJson = DecryptedOutboundCommand(
            commandId = commandId,
            recipientAddress = recipientAddress,
            body = body,
            requestedAt = now,
            subscriptionId = subscriptionId
        ).toJson()

        enqueueAndDispatchEnvelope(
            envelopeId = commandId,
            localSmsId = commandId,
            pairing = pairing,
            plaintextJson = cmdJson,
            direction = RelayEnvelope.DIRECTION_OUTBOUND_COMMAND
        )

        Result.success(commandId)
    }

    suspend fun enqueueDeliveryReceipt(
        pairingId: String,
        targetDeviceId: String,
        commandId: String,
        deliveryStatus: String,
        errorDetail: String?
    ) = withContext(Dispatchers.IO) {
        val pairing = pairedDao.getByPairingId(pairingId) ?: return@withContext
        val receiptId = "rcpt_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val receiptJson = DecryptedDeliveryReceipt(
            commandId = commandId,
            deliveryStatus = deliveryStatus,
            errorDetail = errorDetail,
            timestamp = System.currentTimeMillis()
        ).toJson()

        enqueueAndDispatchEnvelope(
            envelopeId = receiptId,
            localSmsId = null,
            pairing = pairing.copy(peerDeviceId = targetDeviceId),
            plaintextJson = receiptJson,
            direction = RelayEnvelope.DIRECTION_DELIVERY_RECEIPT
        )
    }

    private suspend fun enqueueAndDispatchEnvelope(
        envelopeId: String,
        localSmsId: String?,
        pairing: PairedDeviceEntity,
        plaintextJson: String,
        direction: String
    ) {
        val encrypted = crypto.encryptWithStoredSharedKey(pairing.sharedKeyEncrypted, plaintextJson)
        val queueItem = PendingRelayQueueEntity(
            id = envelopeId,
            pairingId = pairing.pairingId,
            targetDeviceId = pairing.peerDeviceId,
            encryptedPayload = encrypted.ciphertextBase64,
            iv = encrypted.ivBase64,
            direction = direction
        )
        pendingDao.enqueue(queueItem)

        val sent = tryDispatchSingle(queueItem)
        if (sent) {
            if (localSmsId != null) {
                smsDao.updateSyncStatus(localSmsId, SmsMessageEntity.SYNC_SYNCED, null)
            }
            val now = System.currentTimeMillis()
            pairedDao.updateSyncState(pairing.pairingId, now, true)
            prefs.updateLastSyncTimestamp(now)
        } else {
            scheduleRetryWorker()
        }
    }

    suspend fun flushPendingQueue(): Boolean = withContext(Dispatchers.IO) {
        if (Firebase.auth.currentUser == null || !isNetworkConnected()) return@withContext false
        val pending = pendingDao.getPendingAll()
        var allOk = true
        for (item in pending) {
            if (!tryDispatchSingle(item)) {
                allOk = false
            } else {
                val now = System.currentTimeMillis()
                pairedDao.updateSyncState(item.pairingId, now, true)
                prefs.updateLastSyncTimestamp(now)
            }
        }
        allOk
    }

    private suspend fun tryDispatchSingle(item: PendingRelayQueueEntity): Boolean {
        if (Firebase.auth.currentUser == null || !isNetworkConnected()) return false
        val myDeviceId = prefs.getOrCreateDeviceId()
        val result = relayRepo.sendEncryptedRelayEnvelope(
            messageId = item.id,
            pairingId = item.pairingId,
            sourceDeviceId = myDeviceId,
            targetDeviceId = item.targetDeviceId,
            encryptedPayload = item.encryptedPayload,
            iv = item.iv,
            direction = item.direction
        )
        return if (result.isSuccess) {
            pendingDao.remove(item.id)
            val baseSmsId = item.id.substringBeforeLast("_")
            smsDao.updateSyncStatus(baseSmsId, SmsMessageEntity.SYNC_SYNCED, null)
            true
        } else {
            pendingDao.recordFailure(item.id, result.exceptionOrNull()?.localizedMessage)
            false
        }
    }

    suspend fun processIncomingRelayEnvelopes(envelopes: List<RelayEnvelope>) = withContext(Dispatchers.IO) {
        if (envelopes.isEmpty()) return@withContext
        val settings = prefs.settingsFlow.first()

        for (env in envelopes) {
            val pairing = pairedDao.getByPairingId(env.pairingId)
            if (pairing == null || pairing.status != "active") {
                // Never accept or process messages from unpaired/revoked devices
                continue
            }

            try {
                val plaintext = crypto.decryptWithStoredSharedKey(
                    wrappedSharedKey = pairing.sharedKeyEncrypted,
                    ciphertextBase64 = env.encryptedPayload,
                    ivBase64 = env.iv
                )

                when (env.direction) {
                    RelayEnvelope.DIRECTION_INBOUND_SMS -> {
                        val sms = DecryptedSmsPayload.fromJson(plaintext)
                        val otp = OtpExtractor.extractOtp(sms.body)
                        val normalized = SmsTelephonyHelper.normalizeAddress(sms.senderAddress)

                        val entity = SmsMessageEntity(
                            id = sms.messageId,
                            threadAddress = normalized,
                            senderName = sms.senderName ?: SmsTelephonyHelper.resolveContactName(appContext, sms.senderAddress),
                            body = sms.body,
                            timestamp = sms.originalTimestamp,
                            isIncoming = sms.isIncoming,
                            isRead = false,
                            simSlot = sms.simSlot,
                            subscriptionId = sms.subscriptionId,
                            simLabel = sms.simLabel ?: "Synced from ${pairing.peerDeviceName}",
                            origin = SmsMessageEntity.ORIGIN_REMOTE_SYNC,
                            syncStatus = SmsMessageEntity.SYNC_DELIVERED,
                            detectedOtp = otp,
                            sourceDeviceId = env.sourceDeviceId
                        )
                        smsDao.insertMessage(entity)

                        if (settings.notifyOnRemoteSms) {
                            NotificationHelper.showIncomingSmsNotification(
                                context = appContext,
                                messageId = sms.messageId,
                                senderDisplay = entity.senderName ?: sms.senderAddress,
                                threadAddress = normalized,
                                body = sms.body,
                                detectedOtp = otp,
                                isRemoteSync = true,
                                soundEnabled = settings.notificationSound,
                                vibrationEnabled = settings.notificationVibration
                            )
                        }
                    }

                    RelayEnvelope.DIRECTION_OUTBOUND_COMMAND -> {
                        val cmd = DecryptedOutboundCommand.fromJson(plaintext)
                        if (settings.twoWaySmsEnabled) {
                            val localMsgId = cmd.commandId
                            val normalized = SmsTelephonyHelper.normalizeAddress(cmd.recipientAddress)
                            smsDao.insertMessage(
                                SmsMessageEntity(
                                    id = localMsgId,
                                    threadAddress = normalized,
                                    senderName = SmsTelephonyHelper.resolveContactName(appContext, cmd.recipientAddress),
                                    body = cmd.body,
                                    timestamp = cmd.requestedAt,
                                    isIncoming = false,
                                    isRead = true,
                                    subscriptionId = cmd.subscriptionId,
                                    simLabel = SmsTelephonyHelper.resolveSimLabel(appContext, cmd.subscriptionId),
                                    origin = SmsMessageEntity.ORIGIN_SENT_VIA_PHONE_A,
                                    syncStatus = SmsMessageEntity.SYNC_DELIVERED,
                                    sourceDeviceId = env.sourceDeviceId,
                                    remoteStatus = "SENDING"
                                )
                            )
                            val sendRes = SmsTelephonyHelper.sendSms(
                                context = appContext,
                                destinationAddress = cmd.recipientAddress,
                                body = cmd.body,
                                subscriptionId = cmd.subscriptionId,
                                localMessageId = localMsgId,
                                remoteCommandId = cmd.commandId,
                                pairingId = env.pairingId,
                                targetDeviceId = env.sourceDeviceId
                            )
                            if (sendRes.isFailure) {
                                enqueueDeliveryReceipt(
                                    pairingId = env.pairingId,
                                    targetDeviceId = env.sourceDeviceId,
                                    commandId = cmd.commandId,
                                    deliveryStatus = "FAILED",
                                    errorDetail = sendRes.exceptionOrNull()?.localizedMessage
                                )
                            }
                        }
                    }

                    RelayEnvelope.DIRECTION_DELIVERY_RECEIPT -> {
                        val receipt = DecryptedDeliveryReceipt.fromJson(plaintext)
                        val syncState = if (receipt.deliveryStatus == "FAILED") {
                            SmsMessageEntity.SYNC_FAILED
                        } else {
                            SmsMessageEntity.SYNC_DELIVERED
                        }
                        smsDao.updateSyncStatus(receipt.commandId, syncState, receipt.deliveryStatus)
                    }
                }

                val now = System.currentTimeMillis()
                pairedDao.updateSyncState(pairing.pairingId, now, true)
                prefs.updateLastSyncTimestamp(now)

                // Zero server retention: delete temporary relay doc immediately after local persistence
                relayRepo.acknowledgeAndDeleteRelayEnvelope(env.id)
            } catch (_: Exception) {
                // Keep envelope if decryption/persistence failed so retry can recover
            }
        }
    }

    fun scheduleRetryWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun launchAsync(block: suspend SyncCoordinator.() -> Unit) {
        scope.launch { block() }
    }

    private fun readBatteryPercentage(): Int {
        return try {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
        } catch (_: Exception) {
            100
        }
    }

    companion object {
        private const val UNIQUE_SYNC_WORK = "dualphone_pending_relay_flush"
    }
}
