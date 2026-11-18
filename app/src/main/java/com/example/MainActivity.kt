package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.preferences.DeviceRole
import com.example.data.remote.RelayRepository
import com.example.notifications.NotificationHelper
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.performSignOut
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ConnectedDevicesScreen
import com.example.ui.screens.DevicePairingScreen
import com.example.ui.screens.PrivacySecurityScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SmsInboxScreen
import com.example.ui.screens.SyncDashboardScreen
import com.example.ui.screens.WelcomeSetupScreen
import com.example.ui.screens.ConversationsScreen
import com.example.ui.theme.DualPhoneTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.RemoteStreamState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AppRoutes {
    const val WELCOME_SETUP = "welcome_setup"
    const val SMS_INBOX = "sms_inbox"
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{threadAddress}"
    const val PAIRING = "pairing"
    const val CONNECTED_DEVICES = "connected_devices"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"

    fun chatRoute(threadAddress: String): String {
        val encoded = URLEncoder.encode(threadAddress, StandardCharsets.UTF_8.name())
        return "chat/$encoded"
    }
}

class MainActivity : ComponentActivity() {

    private var pendingThreadAddress by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.ensureChannels(this)
        extractNavigationIntent(intent)

        setContent {
            DualPhoneTheme {
                AppAuthGate(
                    pendingThreadAddress = pendingThreadAddress,
                    onConsumeThreadAddress = { pendingThreadAddress = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNavigationIntent(intent)
    }

    private fun extractNavigationIntent(intent: Intent?) {
        if (intent == null) return
        val fromNotif = intent.getStringExtra(NotificationHelper.EXTRA_THREAD_ADDRESS)
        if (!fromNotif.isNullOrBlank()) {
            pendingThreadAddress = fromNotif
            return
        }
        if (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_SEND) {
            val target = intent.data?.schemeSpecificPart?.substringBefore("?")?.trim()
            if (!target.isNullOrBlank()) {
                pendingThreadAddress = target
            }
        }
    }
}

private fun FirebaseAuth.authStateFlow() = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun AppAuthGate(
    pendingThreadAddress: String?,
    onConsumeThreadAddress: () -> Unit
) {
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = { })
    } else {
        key(user.uid) {
            DualPhoneMainApp(
                currentUserId = user.uid,
                userEmail = user.email,
                pendingThreadAddress = pendingThreadAddress,
                onConsumeThreadAddress = onConsumeThreadAddress
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPhoneMainApp(
    currentUserId: String,
    userEmail: String?,
    pendingThreadAddress: String?,
    onConsumeThreadAddress: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: MainViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val repo = RelayRepository(app.applicationContext)
                MainViewModel(app.applicationContext, repo, currentUserId)
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val messages by viewModel.filteredMessages.collectAsStateWithLifecycle()
    val recentOtps by viewModel.recentOtpMessages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val pendingRelayCount by viewModel.pendingRelayCount.collectAsStateWithLifecycle()
    val activePairings by viewModel.activePairings.collectAsStateWithLifecycle()
    val remoteDevicesState by viewModel.remoteDevicesState.collectAsStateWithLifecycle()
    val systemStatus by viewModel.systemStatus.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val inboxFilter by viewModel.inboxFilter.collectAsStateWithLifecycle()
    val activePairingDraft by viewModel.activePairingDraft.collectAsStateWithLifecycle()
    val pairingBusy by viewModel.pairingBusy.collectAsStateWithLifecycle()
    val toastBanner by viewModel.toastBanner.collectAsStateWithLifecycle()

    val remoteDevices = (remoteDevicesState as? RemoteStreamState.Ready)?.items.orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toastBanner) {
        val msg = toastBanner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppRoutes.SMS_INBOX

    LaunchedEffect(settings.onboardingCompleted, settings.deviceRole) {
        if (!settings.onboardingCompleted && settings.deviceRole == DeviceRole.UNSET &&
            currentRoute == AppRoutes.SMS_INBOX
        ) {
            navController.navigate(AppRoutes.WELCOME_SETUP) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(pendingThreadAddress) {
        if (!pendingThreadAddress.isNullOrBlank()) {
            navController.navigate(AppRoutes.chatRoute(pendingThreadAddress))
            onConsumeThreadAddress()
        }
    }

    DisposableEffect(Unit) {
        viewModel.refreshSystemStatus()
        onDispose { }
    }

    val primaryOnline = when (settings.deviceRole) {
        DeviceRole.PRIMARY_SIM -> systemStatus.isNetworkConnected
        else -> remoteDevices.firstOrNull { it.role == "primary_sim" }?.isOnline
            ?: activePairings.firstOrNull { it.peerRole == "primary_sim" }?.isPeerOnline
    }

    val showChromeBars = !currentRoute.startsWith("chat/")

    val topLevelItems = listOf(
        Triple(AppRoutes.SMS_INBOX, "Inbox", Icons.Rounded.Inbox to Icons.Outlined.Inbox),
        Triple(AppRoutes.CONVERSATIONS, "Chats", Icons.Rounded.Forum to Icons.Outlined.Forum),
        Triple(AppRoutes.PAIRING, "Pair", Icons.Rounded.QrCode2 to Icons.Outlined.QrCode2),
        Triple(AppRoutes.DASHBOARD, "Sync", Icons.Rounded.SpaceDashboard to Icons.Outlined.SpaceDashboard)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (showChromeBars) {
                    TopAppBar(
                        title = {
                            Text(
                                when (currentRoute) {
                                    AppRoutes.WELCOME_SETUP -> "Welcome & Setup"
                                    AppRoutes.SMS_INBOX -> "DualPhone SMS"
                                    AppRoutes.CONVERSATIONS -> "Conversations"
                                    AppRoutes.PAIRING -> "Pair Devices"
                                    AppRoutes.CONNECTED_DEVICES -> "Connected Devices"
                                    AppRoutes.DASHBOARD -> "Sync Dashboard"
                                    AppRoutes.SETTINGS -> "Settings"
                                    AppRoutes.PRIVACY -> "Privacy & Security"
                                    else -> "DualPhone SMS Sync"
                                }
                            )
                        },
                        navigationIcon = {
                            if (currentRoute in listOf(
                                    AppRoutes.WELCOME_SETUP,
                                    AppRoutes.CONNECTED_DEVICES,
                                    AppRoutes.SETTINGS,
                                    AppRoutes.PRIVACY
                                )
                            ) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { navController.navigate(AppRoutes.SETTINGS) },
                                modifier = Modifier.testTag("top_settings_button")
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (showChromeBars && !useNavRail) {
                    NavigationBar {
                        topLevelItems.forEach { (route, label, icons) ->
                            val selected = currentRoute == route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo(AppRoutes.SMS_INBOX) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    if (route == AppRoutes.SMS_INBOX && unreadCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$unreadCount") } }) {
                                            Icon(
                                                if (selected) icons.first else icons.second,
                                                contentDescription = label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            if (selected) icons.first else icons.second,
                                            contentDescription = label
                                        )
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_$route")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showChromeBars && useNavRail) {
                    NavigationRail {
                        topLevelItems.forEach { (route, label, icons) ->
                            val selected = currentRoute == route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo(AppRoutes.SMS_INBOX) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        if (selected) icons.first else icons.second,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.SMS_INBOX,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(AppRoutes.WELCOME_SETUP) {
                        WelcomeSetupScreen(
                            settings = settings,
                            systemStatus = systemStatus,
                            onSelectRole = viewModel::selectRole,
                            onRefreshStatus = viewModel::refreshSystemStatus,
                            onContinueToApp = {
                                navController.navigate(AppRoutes.SMS_INBOX) {
                                    popUpTo(AppRoutes.SMS_INBOX) { inclusive = true }
                                }
                            },
                            onOpenPairing = { navController.navigate(AppRoutes.PAIRING) }
                        )
                    }

                    composable(AppRoutes.SMS_INBOX) {
                        SmsInboxScreen(
                            messages = messages,
                            recentOtps = recentOtps,
                            searchQuery = searchQuery,
                            activeFilter = inboxFilter,
                            primaryDeviceOnline = primaryOnline,
                            activeSims = systemStatus.activeSims,
                            onSearchChange = viewModel::setSearchQuery,
                            onFilterChange = viewModel::setInboxFilter,
                            onCopyOtp = viewModel::copyOtp,
                            onMarkRead = viewModel::markMessageAsRead,
                            onOpenThread = { addr -> navController.navigate(AppRoutes.chatRoute(addr)) },
                            onSendNewMessage = viewModel::sendSmsMessage
                        )
                    }

                    composable(AppRoutes.CONVERSATIONS) {
                        ConversationsScreen(
                            conversations = conversations,
                            activeSims = systemStatus.activeSims,
                            onOpenThread = { addr -> navController.navigate(AppRoutes.chatRoute(addr)) },
                            onDeleteThread = viewModel::deleteThread,
                            onSendNewMessage = viewModel::sendSmsMessage
                        )
                    }

                    composable(
                        route = AppRoutes.CHAT,
                        arguments = listOf(navArgument("threadAddress") { type = NavType.StringType })
                    ) { entry ->
                        val rawArg = entry.arguments?.getString("threadAddress").orEmpty()
                        val threadAddr = URLDecoder.decode(rawArg, StandardCharsets.UTF_8.name())
                        val threadMessages by viewModel.observeThread(threadAddr)
                            .collectAsStateWithLifecycle(initialValue = emptyList())

                        ChatDetailScreen(
                            threadAddress = threadAddr,
                            messages = threadMessages,
                            activeSims = systemStatus.activeSims,
                            isRemoteViewer = settings.deviceRole == DeviceRole.REMOTE_VIEWER,
                            onBack = { navController.popBackStack() },
                            onMarkThreadRead = { viewModel.markThreadAsRead(threadAddr) },
                            onSendReply = { body, subId ->
                                viewModel.sendSmsMessage(threadAddr, body, subId)
                            },
                            onDeleteMessage = viewModel::deleteMessage,
                            onCopyOtp = viewModel::copyOtp
                        )
                    }

                    composable(AppRoutes.PAIRING) {
                        DevicePairingScreen(
                            settings = settings,
                            activePairingDraft = activePairingDraft,
                            pairingBusy = pairingBusy,
                            onStartPrimaryPairing = viewModel::startPrimaryPairingSession,
                            onJoinViaQr = viewModel::joinPairingViaQr,
                            onJoinViaCode = viewModel::joinPairingViaCode,
                            onConfirmPairing = viewModel::confirmActivePairing,
                            onClearDraft = viewModel::clearPairingDraft
                        )
                    }

                    composable(AppRoutes.CONNECTED_DEVICES) {
                        ConnectedDevicesScreen(
                            pairings = activePairings,
                            remoteDevices = remoteDevices,
                            onPairAnother = { navController.navigate(AppRoutes.PAIRING) },
                            onDisconnect = viewModel::disconnectPairing
                        )
                    }

                    composable(AppRoutes.DASHBOARD) {
                        SyncDashboardScreen(
                            settings = settings,
                            systemStatus = systemStatus,
                            pairings = activePairings,
                            remoteDevices = remoteDevices,
                            pendingCount = pendingRelayCount,
                            onFlushQueue = viewModel::flushPendingQueueNow,
                            onOpenSetup = { navController.navigate(AppRoutes.WELCOME_SETUP) },
                            onOpenPairing = { navController.navigate(AppRoutes.PAIRING) },
                            onOpenConnectedDevices = { navController.navigate(AppRoutes.CONNECTED_DEVICES) },
                            onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                            onOpenPrivacy = { navController.navigate(AppRoutes.PRIVACY) }
                        )
                    }

                    composable(AppRoutes.SETTINGS) {
                        SettingsScreen(
                            settings = settings,
                            systemStatus = systemStatus,
                            userEmail = userEmail,
                            onUpdateDeviceName = viewModel::updateDeviceName,
                            onAutoSyncChange = viewModel::setAutoSyncEnabled,
                            onTwoWayChange = viewModel::setTwoWaySmsEnabled,
                            onNotifyRemoteChange = viewModel::setNotifyOnRemoteSms,
                            onSoundChange = viewModel::setNotificationSound,
                            onVibrationChange = viewModel::setNotificationVibration,
                            onImportInbox = viewModel::importDeviceSmsInbox,
                            onOpenSetup = { navController.navigate(AppRoutes.WELCOME_SETUP) },
                            onSignOut = {
                                performSignOut(
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            }
                        )
                    }

                    composable(AppRoutes.PRIVACY) {
                        PrivacySecurityScreen(
                            settings = settings,
                            systemStatus = systemStatus,
                            onAuthLockChange = viewModel::setRequireAuthLock,
                            onWipeAndRevoke = viewModel::wipeLocalCacheAndRevokeAll
                        )
                    }
                }
            }
        }
    }
}
