package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.CryptoManager
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationSummary
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.SmsMessageEntity
import com.example.data.preferences.AppPreferences
import com.example.data.preferences.DeviceRole
import com.example.data.preferences.UserSettings
import com.example.data.remote.DeviceRecord
import com.example.data.remote.PairingSession
import com.example.data.remote.RelayRepository
import com.example.domain.OtpExtractor
import com.example.notifications.NotificationHelper
import com.example.sync.SmsSyncForegroundService
import com.example.sync.SyncCoordinator
import com.example.telephony.SimInfo
import com.example.telephony.SmsTelephonyHelper
import com.example.ui.pairing.PairingQrPayload
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface RemoteStreamState<out T> {
    data object Loading : RemoteStreamState<Nothing>
    data class Ready<T>(val items: T) : RemoteStreamState<T>
    data class Error(val message: String) : RemoteStreamState<Nothing>
}

enum class InboxFilter {
    ALL, OTP, SYNCED, LOCAL_SIM
}

data class SystemStatusSnapshot(
    val isDefaultSmsApp: Boolean = false,
    val hasSmsPermissions: Boolean = false,
    val isNetworkConnected: Boolean = false,
    val activeSims: List<SimInfo> = emptyList(),
    val identityFingerprint: String = ""
)

class MainViewModel(
    private val appContext: Context,
    private val relayRepository: RelayRepository,
    private val currentUserId: String
) : ViewModel() {

    private val db = AppDatabase.getInstance(appContext)
    private val smsDao = db.smsDao()
    private val pairedDao = db.pairedDeviceDao()
    private val pendingDao = db.pendingRelayDao()
    private val prefs = AppPreferences(appContext)
    private val crypto = CryptoManager(appContext)
    private val syncCoordinator = SyncCoordinator(appContext)

    val settings: StateFlow<UserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val allMessages: StateFlow<List<SmsMessageEntity>> = smsDao.observeAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentOtpMessages: StateFlow<List<SmsMessageEntity>> = smsDao.observeRecentOtps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadCount: StateFlow<Int> = smsDao.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingRelayCount: StateFlow<Int> = pendingDao.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activePairings: StateFlow<List<PairedDeviceEntity>> = pairedDao.observeActivePairings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationSummary>> = allMessages
        .map { messages ->
            messages.groupBy { it.threadAddress }
                .map { (address, threadMsgs) ->
                    val latest = threadMsgs.maxByOrNull { it.timestamp } ?: threadMsgs.first()
                    val latestOtp = threadMsgs
                        .filter { it.detectedOtp != null }
                        .maxByOrNull { it.timestamp }
                        ?.detectedOtp
                    ConversationSummary(
                        threadAddress = address,
                        senderName = threadMsgs.firstNotNullOfOrNull { it.senderName },
                        lastBody = latest.body,
                        lastTimestamp = latest.timestamp,
                        unreadCount = threadMsgs.count { !it.isRead },
                        latestOrigin = latest.origin,
                        latestOtp = latestOtp,
                        simLabel = latest.simLabel
                    )
                }
                .sortedByDescending { it.lastTimestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val remoteDevicesState: StateFlow<RemoteStreamState<List<DeviceRecord>>> =
        relayRepository.observeDevices()
            .map<List<DeviceRecord>, RemoteStreamState<List<DeviceRecord>>> {
                RemoteStreamState.Ready(it)
            }
            .catch { error ->
                val firestoreErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreErr == null && error is CancellationException) throw error
                val root = firestoreErr ?: error
                Log.w(TAG, "Failed observing remote devices", root)
                emit(RemoteStreamState.Error(root.message ?: "Unable to sync device status"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemoteStreamState.Loading)

    val remotePairingsState: StateFlow<RemoteStreamState<List<PairingSession>>> =
        relayRepository.observePairings()
            .map<List<PairingSession>, RemoteStreamState<List<PairingSession>>> {
                RemoteStreamState.Ready(it)
            }
            .catch { error ->
                val firestoreErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreErr == null && error is CancellationException) throw error
                val root = firestoreErr ?: error
                Log.w(TAG, "Failed observing pairings", root)
                emit(RemoteStreamState.Error(root.message ?: "Unable to observe pairing sessions"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemoteStreamState.Loading)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _inboxFilter = MutableStateFlow(InboxFilter.ALL)
    val inboxFilter: StateFlow<InboxFilter> = _inboxFilter.asStateFlow()

    val filteredMessages: StateFlow<List<SmsMessageEntity>> = combine(
        allMessages,
        _searchQuery,
        _inboxFilter
    ) { list, query, filter ->
        val byFilter = when (filter) {
            InboxFilter.ALL -> list
            InboxFilter.OTP -> list.filter { it.detectedOtp != null }
            InboxFilter.SYNCED -> list.filter {
                it.origin == SmsMessageEntity.ORIGIN_REMOTE_SYNC ||
                    it.origin == SmsMessageEntity.ORIGIN_SENT_VIA_PHONE_A
            }
            InboxFilter.LOCAL_SIM -> list.filter { it.origin == SmsMessageEntity.ORIGIN_LOCAL_SIM }
        }
        if (query.isBlank()) {
            byFilter
        } else {
            val q = query.trim().lowercase()
            byFilter.filter {
                it.body.lowercase().contains(q) ||
                    it.threadAddress.lowercase().contains(q) ||
                    (it.senderName?.lowercase()?.contains(q) == true) ||
                    (it.detectedOtp?.contains(q) == true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _systemStatus = MutableStateFlow(SystemStatusSnapshot())
    val systemStatus: StateFlow<SystemStatusSnapshot> = _systemStatus.asStateFlow()

    private val _activePairingDraft = MutableStateFlow<PairingSession?>(null)
    val activePairingDraft: StateFlow<PairingSession?> = _activePairingDraft.asStateFlow()

    private val _toastBanner = MutableStateFlow<String?>(null)
    val toastBanner: StateFlow<String?> = _toastBanner.asStateFlow()

    private val _pairingBusy = MutableStateFlow(false)
    val pairingBusy: StateFlow<Boolean> = _pairingBusy.asStateFlow()

    init {
        viewModelScope.launch {
            val myDeviceId = prefs.getOrCreateDeviceId()
            refreshSystemStatus()
            syncCoordinator.publishPresence(isOnline = true)
            syncCoordinator.flushPendingQueue()
            SmsSyncForegroundService.start(appContext)

            // Keep local paired_devices table synchronized with remote pairing updates
            remotePairingsState.collect { state ->
                if (state is RemoteStreamState.Ready) {
                    reconcileRemotePairings(myDeviceId, state.items)
                }
            }
        }

        // Also listen to incoming relay envelopes directly while UI is active
        viewModelScope.launch {
            val myDeviceId = prefs.getOrCreateDeviceId()
            relayRepository.observeIncomingRelayForDevice(myDeviceId)
                .catch { error ->
                    val firestoreErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreErr == null && error is CancellationException) throw error
                    Log.w(TAG, "Relay listener error", firestoreErr ?: error)
                }
                .collect { envelopes ->
                    if (envelopes.isNotEmpty()) {
                        syncCoordinator.processIncomingRelayEnvelopes(envelopes)
                    }
                }
        }
    }

    fun observeThread(threadAddress: String) = smsDao.observeThread(threadAddress)

    fun refreshSystemStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _systemStatus.value = SystemStatusSnapshot(
                isDefaultSmsApp = SmsTelephonyHelper.isDefaultSmsApp(appContext),
                hasSmsPermissions = SmsTelephonyHelper.hasSmsPermissions(appContext),
                isNetworkConnected = syncCoordinator.isNetworkConnected(),
                activeSims = SmsTelephonyHelper.getActiveSims(appContext),
                identityFingerprint = crypto.getIdentityFingerprint()
            )
        }
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setInboxFilter(filter: InboxFilter) {
        _inboxFilter.value = filter
    }

    fun clearBanner() {
        _toastBanner.value = null
    }

    fun selectRole(role: DeviceRole) {
        viewModelScope.launch {
            prefs.setDeviceRole(role, markOnboardingDone = true)
            syncCoordinator.publishPresence(isOnline = true)
            if (role == DeviceRole.PRIMARY_SIM && SmsTelephonyHelper.hasSmsPermissions(appContext)) {
                importDeviceSmsInbox()
            }
            SmsSyncForegroundService.start(appContext)
        }
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            prefs.setDeviceName(name)
            syncCoordinator.publishPresence(isOnline = true)
            _toastBanner.value = "Device name updated"
        }
    }

    fun importDeviceSmsInbox() {
        viewModelScope.launch {
            val deviceId = prefs.getOrCreateDeviceId()
            val imported = SmsTelephonyHelper.importSystemSmsMessages(appContext, deviceId)
            if (imported.isNotEmpty()) {
                smsDao.insertAllIgnore(imported)
                _toastBanner.value = "Imported ${imported.size} SMS messages from device"
            } else {
                _toastBanner.value = "No SMS messages found or READ_SMS permission missing"
            }
        }
    }

    fun markThreadAsRead(threadAddress: String) {
        viewModelScope.launch {
            smsDao.markThreadAsRead(threadAddress)
        }
    }

    fun markMessageAsRead(messageId: String) {
        viewModelScope.launch {
            smsDao.markMessageAsRead(messageId)
        }
    }

    fun deleteMessage(message: SmsMessageEntity) {
        viewModelScope.launch {
            smsDao.deleteMessageById(message.id)
            withContext(Dispatchers.IO) {
                SmsTelephonyHelper.deleteSystemSmsIfPresent(appContext, message.id)
            }
        }
    }

    fun deleteThread(threadAddress: String) {
        viewModelScope.launch {
            smsDao.deleteThread(threadAddress)
        }
    }

    fun copyOtp(otp: String) {
        NotificationHelper.copyToClipboard(appContext, "OTP Code", otp)
        _toastBanner.value = "Copied OTP $otp to clipboard"
    }

    fun sendSmsMessage(
        recipientAddress: String,
        body: String,
        selectedSubscriptionId: Int? = null
    ) {
        val cleanRecipient = recipientAddress.trim()
        val cleanBody = body.trim()
        if (cleanRecipient.isEmpty() || cleanBody.isEmpty()) return

        viewModelScope.launch {
            val currentSettings = settings.value
            val isPrimary = currentSettings.deviceRole == DeviceRole.PRIMARY_SIM ||
                SmsTelephonyHelper.hasSmsPermissions(appContext)

            if (isPrimary && SmsTelephonyHelper.hasSmsPermissions(appContext)) {
                val msgId = "sms_out_" + UUID.randomUUID().toString().replace("-", "").take(14)
                val deviceId = prefs.getOrCreateDeviceId()
                val normalized = SmsTelephonyHelper.normalizeAddress(cleanRecipient)
                val simLabel = SmsTelephonyHelper.resolveSimLabel(appContext, selectedSubscriptionId)

                smsDao.insertMessage(
                    SmsMessageEntity(
                        id = msgId,
                        threadAddress = normalized,
                        senderName = SmsTelephonyHelper.resolveContactName(appContext, cleanRecipient),
                        body = cleanBody,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = false,
                        isRead = true,
                        subscriptionId = selectedSubscriptionId,
                        simLabel = simLabel,
                        origin = SmsMessageEntity.ORIGIN_LOCAL_SIM,
                        syncStatus = SmsMessageEntity.SYNC_LOCAL_ONLY,
                        sourceDeviceId = deviceId,
                        remoteStatus = "SENDING"
                    )
                )

                val res = withContext(Dispatchers.IO) {
                    SmsTelephonyHelper.sendSms(
                        context = appContext,
                        destinationAddress = cleanRecipient,
                        body = cleanBody,
                        subscriptionId = selectedSubscriptionId,
                        localMessageId = msgId
                    )
                }
                if (res.isFailure) {
                    smsDao.updateSyncStatus(msgId, SmsMessageEntity.SYNC_FAILED, "FAILED")
                    _toastBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to send SMS"
                }
            } else {
                // Phone B: Two-Way SMS relay command to active paired Phone A
                val primaryPairing = activePairings.value.firstOrNull { it.peerRole == "primary_sim" }
                    ?: activePairings.value.firstOrNull()
                if (primaryPairing == null) {
                    _toastBanner.value = "Pair with Phone A (SIM device) first to send SMS remotely"
                    return@launch
                }
                if (!currentSettings.twoWaySmsEnabled) {
                    _toastBanner.value = "Two-Way SMS relay is disabled in Settings"
                    return@launch
                }
                syncCoordinator.sendOutboundSmsCommandFromPhoneB(
                    recipientAddress = cleanRecipient,
                    body = cleanBody,
                    pairing = primaryPairing,
                    subscriptionId = selectedSubscriptionId
                )
                _toastBanner.value = "Encrypted SMS command queued for ${primaryPairing.peerDeviceName}"
            }
        }
    }

    // --- Secure Device Pairing Flows ---

    fun startPrimaryPairingSession() {
        viewModelScope.launch {
            _pairingBusy.value = true
            try {
                val deviceId = prefs.getOrCreateDeviceId()
                val currentSettings = settings.value
                val result = relayRepository.createPairingSession(
                    primaryDeviceId = deviceId,
                    primaryDeviceName = currentSettings.deviceName,
                    primaryPublicKey = crypto.getPublicKeyBase64()
                )
                result.onSuccess { session ->
                    _activePairingDraft.value = session
                }.onFailure { err ->
                    _toastBanner.value = err.localizedMessage ?: "Failed to start pairing session"
                }
            } finally {
                _pairingBusy.value = false
            }
        }
    }

    fun joinPairingViaQr(rawQr: String) {
        val parsed = PairingQrPayload.parse(rawQr)
        if (parsed == null) {
            _toastBanner.value = "Unrecognized pairing QR code"
            return
        }
        viewModelScope.launch {
            _pairingBusy.value = true
            try {
                val sessionRes = relayRepository.getPairingById(parsed.pairingId)
                val session = sessionRes.getOrNull()
                if (session == null || session.pairingCode != parsed.pairingCode) {
                    _toastBanner.value = "Pairing session expired or not found"
                    return@launch
                }
                joinResolvedSession(session)
            } finally {
                _pairingBusy.value = false
            }
        }
    }

    fun joinPairingViaCode(code: String) {
        val clean = code.trim()
        if (clean.length < 6) {
            _toastBanner.value = "Enter the 6-digit pairing code from Phone A"
            return
        }
        viewModelScope.launch {
            _pairingBusy.value = true
            try {
                val res = relayRepository.findPairingByCode(clean)
                val session = res.getOrNull()
                if (session == null) {
                    _toastBanner.value = "No active pairing found for code $clean"
                    return@launch
                }
                joinResolvedSession(session)
            } finally {
                _pairingBusy.value = false
            }
        }
    }

    private suspend fun joinResolvedSession(session: PairingSession) {
        val myDeviceId = prefs.getOrCreateDeviceId()
        if (session.primaryDeviceId == myDeviceId) {
            _toastBanner.value = "Cannot pair a device with itself"
            return
        }
        val myName = settings.value.deviceName
        val myPubKey = crypto.getPublicKeyBase64()
        val sas = crypto.computeVerificationSas(myPubKey, session.primaryPublicKey, session.id)

        val joinRes = relayRepository.joinPairingSession(
            pairingId = session.id,
            remoteDeviceId = myDeviceId,
            remoteDeviceName = myName,
            remotePublicKey = myPubKey,
            verificationSas = sas
        )
        if (joinRes.isSuccess) {
            _activePairingDraft.value = session.copy(
                remoteDeviceId = myDeviceId,
                remoteDeviceName = myName,
                remotePublicKey = myPubKey,
                verificationSas = sas,
                status = PairingSession.STATUS_AWAITING_CONFIRMATION
            )
        } else {
            _toastBanner.value = joinRes.exceptionOrNull()?.localizedMessage ?: "Failed to join pairing"
        }
    }

    fun confirmActivePairing(session: PairingSession) {
        viewModelScope.launch {
            _pairingBusy.value = true
            try {
                val myDeviceId = prefs.getOrCreateDeviceId()
                val isPrimary = session.primaryDeviceId == myDeviceId
                val peerPub = if (isPrimary) session.remotePublicKey else session.primaryPublicKey
                if (peerPub.isBlank()) {
                    _toastBanner.value = "Waiting for the second phone to scan or enter the code"
                    return@launch
                }
                val myPub = crypto.getPublicKeyBase64()
                val sas = crypto.computeVerificationSas(myPub, peerPub, session.id)
                val res = relayRepository.confirmPairing(
                    pairingId = session.id,
                    isPrimary = isPrimary,
                    verificationSas = sas
                )
                res.onSuccess { updated ->
                    if (updated != null) {
                        _activePairingDraft.value = updated
                        if (updated.status == PairingSession.STATUS_ACTIVE) {
                            persistActivePairingLocally(myDeviceId, updated)
                            _toastBanner.value = "Devices paired with E2E encryption!"
                        } else {
                            _toastBanner.value = "Confirmed! Waiting for the other phone to confirm."
                        }
                    }
                }.onFailure { err ->
                    _toastBanner.value = err.localizedMessage ?: "Confirmation failed"
                }
            } finally {
                _pairingBusy.value = false
            }
        }
    }

    fun clearPairingDraft() {
        _activePairingDraft.value = null
    }

    fun disconnectPairing(pairingId: String) {
        viewModelScope.launch {
            relayRepository.revokePairing(pairingId)
            pairedDao.markRevoked(pairingId)
            pairedDao.deleteByPairingId(pairingId)
            _toastBanner.value = "Device disconnected and encryption key wiped"
        }
    }

    fun flushPendingQueueNow() {
        viewModelScope.launch {
            val ok = syncCoordinator.flushPendingQueue()
            syncCoordinator.publishPresence(isOnline = true)
            _toastBanner.value = if (ok) "Sync queue flushed" else "Still offline or pending retries"
        }
    }

    // --- Settings & Privacy Actions ---

    fun setAutoSyncEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setAutoSyncEnabled(enabled) }
    fun setTwoWaySmsEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setTwoWaySmsEnabled(enabled) }
    fun setNotifyOnRemoteSms(enabled: Boolean) = viewModelScope.launch { prefs.setNotifyOnRemoteSms(enabled) }
    fun setNotificationSound(enabled: Boolean) = viewModelScope.launch { prefs.setNotificationSound(enabled) }
    fun setNotificationVibration(enabled: Boolean) = viewModelScope.launch { prefs.setNotificationVibration(enabled) }
    fun setRequireAuthLock(enabled: Boolean) = viewModelScope.launch { prefs.setRequireAuthLock(enabled) }

    fun wipeLocalCacheAndRevokeAll() {
        viewModelScope.launch {
            val existing = pairedDao.getActivePairingsOnce()
            for (p in existing) {
                relayRepository.revokePairing(p.pairingId)
            }
            smsDao.clearAll()
            pairedDao.clearAll()
            pendingDao.clearAll()
            _toastBanner.value = "Local SMS cache cleared and all remote pairings revoked"
        }
    }

    private suspend fun reconcileRemotePairings(myDeviceId: String, remoteList: List<PairingSession>) {
        // Keep active draft in sync
        val currentDraft = _activePairingDraft.value
        if (currentDraft != null) {
            remoteList.firstOrNull { it.id == currentDraft.id }?.let { fresh ->
                _activePairingDraft.value = fresh
            }
        }

        for (session in remoteList) {
            val involvesMe = session.primaryDeviceId == myDeviceId || session.remoteDeviceId == myDeviceId
            if (!involvesMe) continue

            if (session.status == PairingSession.STATUS_ACTIVE) {
                persistActivePairingLocally(myDeviceId, session)
            } else if (session.status == PairingSession.STATUS_REVOKED) {
                pairedDao.deleteByPairingId(session.id)
            }
        }
    }

    private suspend fun persistActivePairingLocally(myDeviceId: String, session: PairingSession) {
        val isPrimary = session.primaryDeviceId == myDeviceId
        val peerId = if (isPrimary) session.remoteDeviceId else session.primaryDeviceId
        val peerName = if (isPrimary) session.remoteDeviceName else session.primaryDeviceName
        val peerRole = if (isPrimary) "remote_viewer" else "primary_sim"
        val peerPub = if (isPrimary) session.remotePublicKey else session.primaryPublicKey
        if (peerId.isBlank() || peerPub.isBlank()) return

        val existing = pairedDao.getByPairingId(session.id)
        if (existing == null || existing.status != "active") {
            val wrappedKey = crypto.deriveSharedKeyBase64(peerPub, session.id)
            val sas = crypto.computeVerificationSas(crypto.getPublicKeyBase64(), peerPub, session.id)
            pairedDao.upsertPairing(
                PairedDeviceEntity(
                    pairingId = session.id,
                    peerDeviceId = peerId,
                    peerDeviceName = peerName.ifBlank { "Paired Phone" },
                    peerRole = peerRole,
                    sharedKeyEncrypted = wrappedKey,
                    verificationSas = sas,
                    status = "active",
                    lastSyncAt = System.currentTimeMillis(),
                    isPeerOnline = true
                )
            )
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
