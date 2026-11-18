package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ConversationSummary
import com.example.data.local.SmsMessageEntity
import com.example.data.remote.DeviceRecord
import com.example.telephony.SimInfo
import com.example.ui.viewmodel.InboxFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmsInboxScreen(
    messages: List<SmsMessageEntity>,
    recentOtps: List<SmsMessageEntity>,
    searchQuery: String,
    activeFilter: InboxFilter,
    primaryDeviceOnline: Boolean?,
    activeSims: List<SimInfo>,
    onSearchChange: (String) -> Unit,
    onFilterChange: (InboxFilter) -> Unit,
    onCopyOtp: (String) -> Unit,
    onMarkRead: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onSendNewMessage: (String, String, Int?) -> Unit
) {
    var showComposeDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showComposeDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("compose_sms_fab")
            ) {
                Icon(Icons.Rounded.AddComment, contentDescription = "Compose new SMS")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PhoneAConnectionBanner(primaryDeviceOnline = primaryDeviceOnline)
            }

            if (recentOtps.isNotEmpty()) {
                item {
                    val latestOtpMsg = recentOtps.first()
                    val code = latestOtpMsg.detectedOtp.orEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("prominent_otp_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.tertiary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Password,
                                    contentDescription = "Detected OTP",
                                    tint = MaterialTheme.colorScheme.onTertiary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LATEST OTP • ${latestOtpMsg.senderName ?: latestOtpMsg.threadAddress}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = code,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Button(
                                onClick = {
                                    onCopyOtp(code)
                                    onMarkRead(latestOtpMsg.id)
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("copy_latest_otp_button")
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy")
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inbox_search_input"),
                    placeholder = { Text("Search messages, senders, or OTP codes") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InboxFilter.entries.forEach { filter ->
                        val label = when (filter) {
                            InboxFilter.ALL -> "All Messages"
                            InboxFilter.OTP -> "OTP Codes"
                            InboxFilter.SYNCED -> "Remote Synced"
                            InboxFilter.LOCAL_SIM -> "Physical SIM"
                        }
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                item {
                    EmptyMessagesCard(
                        title = "No SMS messages yet",
                        subtitle = "Incoming SMS from Phone A's SIM card or synced messages from your paired phone will appear here in real time."
                    )
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    InboxMessageCard(
                        message = msg,
                        onOpen = {
                            onMarkRead(msg.id)
                            onOpenThread(msg.threadAddress)
                        },
                        onMarkRead = { onMarkRead(msg.id) },
                        onCopyOtp = onCopyOtp
                    )
                }
            }
        }
    }

    if (showComposeDialog) {
        ComposeSmsDialog(
            activeSims = activeSims,
            onDismiss = { showComposeDialog = false },
            onSend = { recipient, body, subId ->
                showComposeDialog = false
                onSendNewMessage(recipient, body, subId)
            }
        )
    }
}

@Composable
fun ConversationsScreen(
    conversations: List<ConversationSummary>,
    activeSims: List<SimInfo>,
    onOpenThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
    onSendNewMessage: (String, String, Int?) -> Unit
) {
    var showComposeDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showComposeDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("conversations_compose_fab")
            ) {
                Icon(Icons.Rounded.AddComment, contentDescription = "Start new conversation")
            }
        }
    ) { innerPadding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyMessagesCard(
                    title = "No Conversations",
                    subtitle = "Send a message or wait for incoming SMS on Phone A to start a synchronized conversation."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(conversations, key = { it.threadAddress }) { convo ->
                    val displayName = convo.senderName ?: convo.threadAddress
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenThread(convo.threadAddress) }
                            .testTag("conversation_card_${convo.threadAddress}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = displayName.take(2).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = formatTimestamp(convo.lastTimestamp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = convo.lastBody,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OriginBadge(origin = convo.latestOrigin, simLabel = convo.simLabel)
                                    if (convo.latestOtp != null) {
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = "OTP ${convo.latestOtp}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (convo.unreadCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "${convo.unreadCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = { onDeleteThread(convo.threadAddress) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete conversation",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showComposeDialog) {
        ComposeSmsDialog(
            activeSims = activeSims,
            onDismiss = { showComposeDialog = false },
            onSend = { recipient, body, subId ->
                showComposeDialog = false
                onSendNewMessage(recipient, body, subId)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    threadAddress: String,
    messages: List<SmsMessageEntity>,
    activeSims: List<SimInfo>,
    isRemoteViewer: Boolean,
    onBack: () -> Unit,
    onMarkThreadRead: () -> Unit,
    onSendReply: (String, Int?) -> Unit,
    onDeleteMessage: (SmsMessageEntity) -> Unit,
    onCopyOtp: (String) -> Unit
) {
    BackHandler(onBack = onBack)

    var replyText by remember { mutableStateOf("") }
    var selectedSubId by remember(activeSims) {
        mutableStateOf(activeSims.firstOrNull()?.subscriptionId)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(threadAddress, messages.size) {
        onMarkThreadRead()
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val senderTitle = messages.firstNotNullOfOrNull { it.senderName } ?: threadAddress

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = senderTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isRemoteViewer) "$threadAddress • Relay via Phone A" else threadAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to conversations")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubbleItem(
                        message = msg,
                        onCopyOtp = onCopyOtp,
                        onDelete = { onDeleteMessage(msg) }
                    )
                }
            }

            if (activeSims.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeSims.forEach { sim ->
                        FilterChip(
                            selected = selectedSubId == sim.subscriptionId,
                            onClick = { selectedSubId = sim.subscriptionId },
                            label = { Text("SIM ${sim.simSlotIndex + 1} (${sim.carrierName})") },
                            leadingIcon = {
                                Icon(Icons.Rounded.SimCard, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_reply_input"),
                        placeholder = {
                            Text(if (isRemoteViewer) "Reply via Phone A SIM..." else "Text message...")
                        },
                        maxLines = 4,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val clean = replyText.trim()
                            if (clean.isNotEmpty()) {
                                onSendReply(clean, selectedSubId)
                                replyText = ""
                            }
                        },
                        enabled = replyText.isNotBlank(),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("chat_send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send SMS")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(
    message: SmsMessageEntity,
    onCopyOtp: (String) -> Unit,
    onDelete: () -> Unit
) {
    val alignment = if (message.isIncoming) Alignment.Start else Alignment.End
    val bubbleColor = when {
        !message.isIncoming -> MaterialTheme.colorScheme.primaryContainer
        message.origin == SmsMessageEntity.ORIGIN_REMOTE_SYNC -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.isIncoming) 4.dp else 18.dp,
                bottomEnd = if (message.isIncoming) 18.dp else 4.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (message.detectedOtp != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "OTP DETECTED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = message.detectedOtp,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            TextButton(
                                onClick = { onCopyOtp(message.detectedOtp) },
                                modifier = Modifier.testTag("bubble_copy_otp_${message.id}")
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OriginBadge(origin = message.origin, simLabel = message.simLabel)
                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!message.isIncoming && message.remoteStatus != null) {
                        Text(
                            text = "• ${message.remoteStatus}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete message",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxMessageCard(
    message: SmsMessageEntity,
    onOpen: () -> Unit,
    onMarkRead: () -> Unit,
    onCopyOtp: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("inbox_msg_${message.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (!message.isRead) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (!message.isRead) 1.5.dp else 1.dp,
            color = if (!message.isRead) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.senderName ?: message.threadAddress,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (!message.isRead) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OriginBadge(origin = message.origin, simLabel = message.simLabel)
                    if (message.syncStatus == SmsMessageEntity.SYNC_QUEUED) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "QUEUED",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.detectedOtp != null) {
                        TextButton(onClick = { onCopyOtp(message.detectedOtp) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(message.detectedOtp)
                        }
                    }
                    if (!message.isRead) {
                        IconButton(onClick = onMarkRead, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.MarkEmailRead,
                                contentDescription = "Mark as read",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneAConnectionBanner(primaryDeviceOnline: Boolean?) {
    val label = when (primaryDeviceOnline) {
        true -> "Phone A Relay Online • Encrypted channel ready"
        false -> "Phone A Offline • Messages will queue until reconnected"
        null -> "No primary SIM phone paired yet • Pair devices to sync"
    }
    val container = when (primaryDeviceOnline) {
        true -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.DoneAll,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun OriginBadge(origin: String, simLabel: String?) {
    val (label, icon) = when (origin) {
        SmsMessageEntity.ORIGIN_REMOTE_SYNC -> (simLabel ?: "Remote Synced") to Icons.Rounded.Phonelink
        SmsMessageEntity.ORIGIN_SENT_VIA_PHONE_A -> "Sent via Phone A" to Icons.Rounded.Phonelink
        else -> (simLabel ?: "Local SIM") to Icons.Rounded.SimCard
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyMessagesCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Sms,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ComposeSmsDialog(
    activeSims: List<SimInfo>,
    onDismiss: () -> Unit,
    onSend: (String, String, Int?) -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var selectedSubId by remember(activeSims) {
        mutableStateOf(activeSims.firstOrNull()?.subscriptionId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New SMS Message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("Recipient phone number") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compose_recipient_input")
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compose_body_input")
                )
                AnimatedVisibility(visible = activeSims.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeSims.forEach { sim ->
                            FilterChip(
                                selected = selectedSubId == sim.subscriptionId,
                                onClick = { selectedSubId = sim.subscriptionId },
                                label = { Text("SIM ${sim.simSlotIndex + 1}") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(recipient, body, selectedSubId) },
                enabled = recipient.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.testTag("compose_send_button")
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "Never"
    val df = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return df.format(Date(millis))
}
