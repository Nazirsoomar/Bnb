package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.preferences.DeviceRole
import com.example.data.preferences.UserSettings
import com.example.telephony.SmsTelephonyHelper
import com.example.ui.viewmodel.SystemStatusSnapshot

@Composable
fun WelcomeSetupScreen(
    settings: UserSettings,
    systemStatus: SystemStatusSnapshot,
    onSelectRole: (DeviceRole) -> Unit,
    onRefreshStatus: () -> Unit,
    onContinueToApp: () -> Unit,
    onOpenPairing: () -> Unit
) {
    val context = LocalContext.current
    var selectedRole by remember(settings.deviceRole) {
        mutableStateOf(
            if (settings.deviceRole == DeviceRole.UNSET) DeviceRole.PRIMARY_SIM else settings.deviceRole
        )
    }

    val smsPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshStatus()
    }

    val defaultSmsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshStatus()
        // Request SMS permissions ONLY after user accepts Default SMS role
        if (SmsTelephonyHelper.isDefaultSmsApp(context)) {
            smsPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        onRefreshStatus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(MaterialTheme.shapes.large)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_sync_hero),
                    contentDescription = "DualPhone SMS Sync setup banner",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Text(
                text = "Device Role & Setup",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Choose how this phone participates in your encrypted SMS sync pair. You can switch roles later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            RoleOptionCard(
                title = "Phone A — Primary SIM Phone",
                subtitle = "Holds your physical SIM card. Becomes the Default SMS app, captures incoming SMS & OTPs, and relays them securely.",
                icon = Icons.Rounded.SimCard,
                selected = selectedRole == DeviceRole.PRIMARY_SIM,
                testTag = "role_primary_sim_card",
                onClick = {
                    selectedRole = DeviceRole.PRIMARY_SIM
                    onSelectRole(DeviceRole.PRIMARY_SIM)
                }
            )

            RoleOptionCard(
                title = "Phone B — Remote Companion (No SIM Needed)",
                subtitle = "Receives live synced SMS & OTPs over Wi-Fi/internet, displays instant Copy OTP buttons, and sends replies via Phone A.",
                icon = Icons.Rounded.TabletAndroid,
                selected = selectedRole == DeviceRole.REMOTE_VIEWER,
                testTag = "role_remote_viewer_card",
                onClick = {
                    selectedRole = DeviceRole.REMOTE_VIEWER
                    onSelectRole(DeviceRole.REMOTE_VIEWER)
                }
            )

            if (selectedRole == DeviceRole.PRIMARY_SIM) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Phone A Requirements",
                            style = MaterialTheme.typography.titleMedium
                        )

                        SetupChecklistRow(
                            title = "1. Default SMS Application Role",
                            subtitle = if (systemStatus.isDefaultSmsApp) {
                                "Active — DualPhone SMS Sync is your default SMS handler"
                            } else {
                                "Required to receive SMS_DELIVER broadcasts & manage inbox"
                            },
                            completed = systemStatus.isDefaultSmsApp
                        )

                        if (!systemStatus.isDefaultSmsApp) {
                            Button(
                                onClick = {
                                    onSelectRole(DeviceRole.PRIMARY_SIM)
                                    val roleIntent = SmsTelephonyHelper.createDefaultSmsRoleIntent(context)
                                    if (roleIntent != null) {
                                        defaultSmsRoleLauncher.launch(roleIntent)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("request_default_sms_button")
                            ) {
                                Icon(Icons.Rounded.Sms, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Set as Default SMS App")
                            }
                        }

                        SetupChecklistRow(
                            title = "2. SMS & Dual-SIM Permissions",
                            subtitle = if (systemStatus.hasSmsPermissions) {
                                "Granted — Ready to sync incoming SMS & send replies"
                            } else {
                                "Requested automatically once Default SMS Role is accepted"
                            },
                            completed = systemStatus.hasSmsPermissions
                        )

                        if (systemStatus.isDefaultSmsApp && !systemStatus.hasSmsPermissions) {
                            OutlinedButton(
                                onClick = {
                                    smsPermissionsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.RECEIVE_SMS,
                                            Manifest.permission.READ_SMS,
                                            Manifest.permission.SEND_SMS,
                                            Manifest.permission.READ_PHONE_STATE,
                                            Manifest.permission.READ_CONTACTS
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("request_sms_permissions_button")
                            ) {
                                Text("Grant SMS & SIM Permissions")
                            }
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("enable_notifications_button")
                ) {
                    Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enable Live SMS & OTP Notifications")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Background Sync & Device Tips",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Android OEMs (Xiaomi, Samsung, Oppo, Vivo) may pause background services under aggressive battery saving. For reliable lock-screen sync, allow unrestricted battery usage in App Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Open App Battery Settings")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSelectRole(selectedRole)
                        onOpenPairing()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("setup_pair_devices_button")
                ) {
                    Icon(Icons.Rounded.PhonelinkSetup, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pair Phones")
                }

                Button(
                    onClick = {
                        onSelectRole(selectedRole)
                        onContinueToApp()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("setup_continue_button")
                ) {
                    Text("Open Inbox", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RoleOptionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected role",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SetupChecklistRow(
    title: String,
    subtitle: String,
    completed: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = if (completed) "Completed" else "Pending",
            tint = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
