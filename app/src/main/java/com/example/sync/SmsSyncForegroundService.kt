package com.example.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.preferences.AppPreferences
import com.example.data.remote.RelayRepository
import com.example.notifications.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service keeping near-real-time encrypted SMS relay active when minimized,
 * locked, or after reboot.
 */
class SmsSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        val notification = NotificationHelper.buildForegroundServiceNotification(
            this,
            "Encrypted relay channel ready"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        registerConnectivityWatcher()
        startRelayObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startRelayObserver()
        return START_STICKY
    }

    private fun startRelayObserver() {
        if (relayJob?.isActive == true) return
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null) {
            updateNotification("Sign in to enable encrypted sync")
            return
        }

        relayJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            val deviceId = prefs.getOrCreateDeviceId()
            val coordinator = SyncCoordinator(applicationContext)
            val relayRepo = RelayRepository(applicationContext)
            val pairedDao = AppDatabase.getInstance(applicationContext).pairedDeviceDao()

            coordinator.publishPresence(isOnline = true)
            coordinator.flushPendingQueue()

            launch {
                pairedDao.observeActivePairings().collectLatest { pairings ->
                    val count = pairings.size
                    val status = if (count == 0) {
                        "No paired devices yet"
                    } else {
                        "E2E encrypted sync active ($count paired)"
                    }
                    updateNotification(status)
                }
            }

            relayRepo.observeIncomingRelayForDevice(deviceId)
                .catch { _ ->
                    updateNotification("Reconnecting encrypted relay...")
                }
                .collectLatest { envelopes ->
                    if (envelopes.isNotEmpty()) {
                        coordinator.processIncomingRelayEnvelopes(envelopes)
                    }
                }
        }
    }

    private fun registerConnectivityWatcher() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    val coordinator = SyncCoordinator(applicationContext)
                    coordinator.publishPresence(isOnline = true)
                    coordinator.flushPendingQueue()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(
            NOTIFICATION_ID,
            NotificationHelper.buildForegroundServiceNotification(this, statusText)
        )
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.example.sync.STOP_SERVICE"

        fun start(context: Context) {
            if (Firebase.auth.currentUser == null) return
            val intent = Intent(context, SmsSyncForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // On Android 12+ background start restrictions, schedule WorkManager fallback
                SyncCoordinator(context.applicationContext).scheduleRetryWorker()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsSyncForegroundService::class.java))
        }
    }
}
