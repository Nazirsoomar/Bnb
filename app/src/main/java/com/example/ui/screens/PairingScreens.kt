package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.local.PairedDeviceEntity
import com.example.data.preferences.DeviceRole
import com.example.data.preferences.UserSettings
import com.example.data.remote.DeviceRecord
import com.example.data.remote.PairingSession
import com.example.ui.pairing.PairingQrPayload
import com.example.ui.pairing.QrCodeHelper
import com.example.ui.pairing.QrScannerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    settings: UserSettings,
    activePairingDraft: PairingSession?,
    pairingBusy: Boolean,
    onStartPrimaryPairing: () -> Unit,
    onJoinViaQr: (String) -> Unit,
    onJoinViaCode: (String) -> Unit,
    onConfirmPairing: (PairingSession) -> Unit,
    onClearDraft: () -> Unit
) {
    var selectedTab by remember(settings.deviceRole) {
        mutableIntStateOf(if (settings.deviceRole == DeviceRole.REMOTE_VIEWER) 1 else 0)
    }
    var joinModeTab by remember { mutableIntStateOf(0) } // 0 = QR Scan, 1 = 6-digit code
    var manualCode by remember { mutableStateOf("") }

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
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Secure Device Pairing",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Pair Phone A (with SIM) and Phone B via ECDH P-256 key exchange. Explicit confirmation on both phones is required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Phone A • Show QR") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Phone B • Join Pair") }
                )
            }

            if (activePairingDraft != null) {
                PairingDraftStatusCard(
                    draft = activePairingDraft,
                    myDeviceId = settings.deviceId,
                    busy = pairingBusy,
                    onConfirm = { onConfirmPairing(activePairingDraft) },
                    onReset = onClearDraft
                )
            }

            if (selectedTab == 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Generate Pairing QR & Code",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Creates an encrypted pairing invitation on Phone A. Scan it on Phone B or enter the one-time 6-digit code.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onStartPrimaryPairing,
                            enabled = !pairingBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("generate_pairing_qr_button")
                        ) {
                            if (pairingBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Generate New Pairing Session")
                            }
                        }
                    }
                }
            } else {
                PrimaryTabRow(selectedTabIndex = joinModeTab) {
                    Tab(
                        selected = joinModeTab == 0,
                        onClick = { joinModeTab = 0 },
                        text = { Text("Scan QR Code") },
                        icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) }
                    )
                    Tab(
                        selected = joinModeTab == 1,
                        onClick = { joinModeTab = 1 },
                        text = { Text("One-Time Code") },
                        icon = { Icon(Icons.Rounded.Key, contentDescription = null) }
                    )
                }

                if (joinModeTab == 0) {
                    QrScannerView(
                        onQrScanned = onJoinViaQr
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Enter 6-Digit Pairing Code",
                                style = MaterialTheme.typography.titleMedium
                            )
                            OutlinedTextField(
                                value = manualCode,
                                onValueChange = { if (it.length <= 8) manualCode = it.filter { ch -> ch.isDigit() } },
                                label = { Text("One-time code from Phone A") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_pairing_code_input")
                            )
                            Button(
                                onClick = { onJoinViaCode(manualCode) },
                                enabled = manualCode.length >= 6 && !pairingBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("join_with_code_button")
                            ) {
                                Text("Connect & Verify Key")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingDraftStatusCard(
    draft: PairingSession,
    myDeviceId: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onReset: () -> Unit
) {
    val isPrimary = draft.primaryDeviceId == myDeviceId
    val qrPayload = remember(draft.id, draft.pairingCode, draft.primaryPublicKey) {
        PairingQrPayload(
            pairingId = draft.id,
            pairingCode = draft.pairingCode,
            primaryDeviceId = draft.primaryDeviceId,
            primaryDeviceName = draft.primaryDeviceName,
            primaryPublicKey = draft.primaryPublicKey
        ).toUriString()
    }
    val qrBitmap = remember(qrPayload) { QrCodeHelper.generateQrBitmap(qrPayload, 460) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pairing_draft_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (draft.status) {
                        PairingSession.STATUS_PENDING -> "Waiting for Phone B to Scan / Join"
                        PairingSession.STATUS_AWAITING_CONFIRMATION -> "Confirm Safety Code on Both Phones"
                        PairingSession.STATUS_ACTIVE -> "Pairing Active & E2E Encrypted"
                        else -> "Pairing ${draft.status}"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (isPrimary && draft.status == PairingSession.STATUS_PENDING && qrBitmap != null) {
                Surface(
                    color = Color.White,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Pairing QR code for Phone B",
                        modifier = Modifier
                            .size(210.dp)
                            .padding(10.dp)
                    )
                }
                Text(
                    text = "ONE-TIME CODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = draft.pairingCode,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("pairing_code_display")
                )
            }

            if (draft.verificationSas.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SAFETY NUMBER (MUST MATCH ON BOTH PHONES)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = draft.verificationSas,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testTag("sas_code_display")
                        )
                    }
                }

                val myConfirmed = if (isPrimary) draft.primaryConfirmed else draft.remoteConfirmed
                val peerConfirmed = if (isPrimary) draft.remoteConfirmed else draft.primaryConfirmed

                Text(
                    text = "This device: ${if (myConfirmed) "Confirmed" else "Pending"} • Peer device: ${if (peerConfirmed) "Confirmed" else "Pending"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!myConfirmed && draft.status != PairingSession.STATUS_ACTIVE) {
                    Button(
                        onClick = onConfirm,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("confirm_pairing_button")
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Confirm & Trust Device")
                    }
                }
            }

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(if (draft.status == PairingSession.STATUS_ACTIVE) "Done" else "Cancel Session")
            }
        }
    }
}

@Composable
fun ConnectedDevicesScreen(
    pairings: List<PairedDeviceEntity>,
    remoteDevices: List<DeviceRecord>,
    onPairAnother: () -> Unit,
    onDisconnect: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connected Devices",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "${pairings.size} active encrypted link(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onPairAnother,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("add_device_button")
                ) {
                    Icon(Icons.Rounded.AddLink, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pair New")
                }
            }
        }

        if (pairings.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("No Paired Devices", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Pair multiple companion phones or tablets so incoming SMS and OTPs sync automatically to all trusted devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(pairings, key = { it.pairingId }) { paired ->
                val remoteRecord = remoteDevices.firstOrNull { it.id == paired.peerDeviceId }
                val isOnline = remoteRecord?.isOnline ?: paired.isPeerOnline
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("paired_device_${paired.pairingId}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = paired.peerDeviceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (paired.peerRole == "primary_sim") "Phone A • Primary SIM" else "Phone B • Remote Companion",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = if (isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = if (isOnline) "ONLINE" else "OFFLINE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "SAS: ${paired.verificationSas}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (remoteRecord != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.BatteryFull,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = "${remoteRecord.batteryLevel}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Last sync: ${formatTimestamp(paired.lastSyncAt ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = { onDisconnect(paired.pairingId) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("disconnect_device_${paired.pairingId}")
                        ) {
                            Icon(Icons.Rounded.LinkOff, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnect & Wipe Shared Key")
                        }
                    }
                }
            }
        }
    }
}
