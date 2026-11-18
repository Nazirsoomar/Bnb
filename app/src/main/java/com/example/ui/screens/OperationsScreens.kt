package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.PairedDeviceEntity
import com.example.data.preferences.DeviceRole
import com.example.data.preferences.UserSettings
import com.example.data.remote.DeviceRecord
import com.example.ui.viewmodel.SystemStatusSnapshot

@Composable
fun SyncDashboardScreen(
    settings: UserSettings,
    systemStatus: SystemStatusSnapshot,
    pairings: List<PairedDeviceEntity>,
    remoteDevices: List<DeviceRecord>,
    pendingCount: Int,
    onFlushQueue: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenConnectedDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val primaryOnline = when (settings.deviceRole) {
        DeviceRole.PRIMARY_SIM -> systemStatus.isNetworkConnected
        else -> remoteDevices.firstOrNull { it.role == "primary_sim" }?.isOnline
            ?: pairings.firstOrNull { it.peerRole == "primary_sim" }?.isPeerOnline
    }
    val phoneBOnline = when (settings.deviceRole) {
        DeviceRole.REMOTE_VIEWER -> systemStatus.isNetworkConnected
        else -> remoteDevices.firstOrNull { it.role == "remote_viewer" }?.isOnline
            ?: pairings.firstOrNull { it.peerRole == "remote_viewer" }?.isPeerOnline
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "SMS Sync Dashboard",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Live relay telemetry, encryption health, and queued sync pipeline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusMetricCard(
                    title = "Phone A (SIM)",
                    value = when (primaryOnline) {
                        true -> "Online"
                        false -> "Offline"
                        null -> "Unpaired"
                    },
                    isGood = primaryOnline == true,
                    modifier = Modifier.weight(1f)
                )
                StatusMetricCard(
                    title = "Phone B (Remote)",
                    value = when (phoneBOnline) {
                        true -> "Online"
                        false -> "Offline"
                        null -> "Unpaired"
                    },
                    isGood = phoneBOnline == true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusMetricCard(
                    title = "Paired Devices",
                    value = "${pairings.size}",
                    isGood = pairings.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
                StatusMetricCard(
                    title = "Pending Queue",
                    value = "$pendingCount",
                    isGood = pendingCount == 0,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Synchronization Status", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Last successful sync: ${formatTimestamp(settings.lastSyncTimestamp)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "This device role: ${settings.deviceRole.name} • Default SMS: ${if (systemStatus.isDefaultSmsApp) "Yes" else "No"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onFlushQueue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("flush_sync_queue_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sync & Flush Queue Now")
                    }
                }
            }
        }

        item {
            Text(
                text = "Quick Navigation",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardActionTile(
                    icon = Icons.Rounded.PhonelinkSetup,
                    title = "Pair New Phone (QR / Code)",
                    subtitle = "ECDH key exchange & mutual verification",
                    testTag = "dash_pair_button",
                    onClick = onOpenPairing
                )
                DashboardActionTile(
                    icon = Icons.Rounded.Devices,
                    title = "Connected Devices (${pairings.size})",
                    subtitle = "Inspect battery, status, or disconnect a device",
                    testTag = "dash_devices_button",
                    onClick = onOpenConnectedDevices
                )
                DashboardActionTile(
                    icon = Icons.Rounded.Sms,
                    title = "Role & Default SMS Setup",
                    subtitle = "Switch between Phone A (SIM) and Phone B (Remote)",
                    testTag = "dash_setup_button",
                    onClick = onOpenSetup
                )
                DashboardActionTile(
                    icon = Icons.Rounded.Settings,
                    title = "Settings & Notifications",
                    subtitle = "Notification sound, vibration, two-way SMS & inbox import",
                    testTag = "dash_settings_button",
                    onClick = onOpenSettings
                )
                DashboardActionTile(
                    icon = Icons.Rounded.Security,
                    title = "Privacy & Security",
                    subtitle = "Android Keystore fingerprint, E2EE audit & cache wipe",
                    testTag = "dash_privacy_button",
                    onClick = onOpenPrivacy
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: UserSettings,
    systemStatus: SystemStatusSnapshot,
    userEmail: String?,
    onUpdateDeviceName: (String) -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onTwoWayChange: (Boolean) -> Unit,
    onNotifyRemoteChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onImportInbox: () -> Unit,
    onOpenSetup: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var nameDraft by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
            if (!userEmail.isNullOrBlank()) {
                Text(
                    text = "Signed in as $userEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Device Identity", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Device Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onUpdateDeviceName(nameDraft) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Save Name")
                        }
                        OutlinedButton(
                            onClick = onOpenSetup,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Role & SMS Setup")
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Synchronization & Relay", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "Automatic SMS Synchronization",
                        subtitle = "Encrypt and relay incoming SMS immediately in the background",
                        checked = settings.autoSyncEnabled,
                        onCheckedChange = onAutoSyncChange
                    )
                    HorizontalDivider()
                    SettingSwitchRow(
                        title = "Two-Way SMS via Phone A",
                        subtitle = "Allow Phone B to send SMS through Phone A's physical SIM",
                        checked = settings.twoWaySmsEnabled,
                        onCheckedChange = onTwoWayChange
                    )
                    HorizontalDivider()
                    SettingSwitchRow(
                        title = "Remote SMS Notifications",
                        subtitle = "Display live notifications and Copy OTP actions for synced SMS",
                        checked = settings.notifyOnRemoteSms,
                        onCheckedChange = onNotifyRemoteChange
                    )
                    HorizontalDivider()
                    SettingSwitchRow(
                        title = "Notification Sound",
                        subtitle = "Play alert tone for newly arrived or synced SMS",
                        checked = settings.notificationSound,
                        onCheckedChange = onSoundChange
                    )
                    HorizontalDivider()
                    SettingSwitchRow(
                        title = "Notification Vibration",
                        subtitle = "Vibrate on incoming SMS & verification codes",
                        checked = settings.notificationVibration,
                        onCheckedChange = onVibrationChange
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (systemStatus.hasSmsPermissions) {
                    OutlinedButton(
                        onClick = onImportInbox,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("import_system_sms_button")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import Existing Device SMS Inbox")
                    }
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Configure Unrestricted Battery Usage")
                }

                OutlinedButton(
                    onClick = onSignOut,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out")
                }
            }
        }
    }
}

@Composable
fun PrivacySecurityScreen(
    settings: UserSettings,
    systemStatus: SystemStatusSnapshot,
    onAuthLockChange: (Boolean) -> Unit,
    onWipeAndRevoke: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = "Privacy & Security", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Zero-knowledge relay architecture engineered for sensitive OTPs and banking SMS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.EnhancedEncryption,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Device Identity Fingerprint", style = MaterialTheme.typography.titleMedium)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = systemStatus.identityFingerprint.ifBlank { "Generating..." },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Text(
                        text = "ECDH P-256 private key wrapped by hardware-backed Android Keystore AES-256-GCM master key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PrivacyGuaranteeRow(
                        icon = Icons.Rounded.Key,
                        title = "End-to-End Encrypted Relay",
                        detail = "Every SMS is encrypted with AES-256-GCM (unique 12-byte IV) before leaving Phone A."
                    )
                    PrivacyGuaranteeRow(
                        icon = Icons.Rounded.Shield,
                        title = "Zero Plaintext Server Storage",
                        detail = "Cloud Firestore only receives opaque ciphertext envelopes. No phone numbers or SMS text ever exist in plaintext on servers."
                    )
                    PrivacyGuaranteeRow(
                        icon = Icons.Rounded.Sync,
                        title = "Automatic Post-Delivery Deletion",
                        detail = "Temporary relay envelopes are immediately deleted from Firestore once decrypted and saved locally on Phone B."
                    )
                    PrivacyGuaranteeRow(
                        icon = Icons.Rounded.PrivacyTip,
                        title = "Zero Ads, Analytics, or Accessibility Hacks",
                        detail = "No third-party tracking SDKs, no hidden APIs, and no Accessibility Service workarounds."
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingSwitchRow(
                        title = "Require Authenticated Session Lock",
                        subtitle = "Gate remote inbox access behind verified Google Sign-In identity",
                        checked = settings.requireAuthLock,
                        onCheckedChange = onAuthLockChange
                    )
                }
            }
        }

        item {
            Button(
                onClick = onWipeAndRevoke,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("wipe_and_revoke_button")
            ) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Wipe Local SMS Cache & Revoke All Pairings", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusMetricCard(
    title: String,
    value: String,
    isGood: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DashboardActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacyGuaranteeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
