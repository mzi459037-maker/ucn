package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.model.Conversation
import com.example.data.model.DecryptedMessage
import com.example.data.model.DeliveryReceiptState
import com.example.data.model.PublicIdentity
import com.example.data.model.ReadReceipt
import com.example.ui.PeerLookupState
import com.example.util.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    conversation: Conversation?,
    messages: List<DecryptedMessage>,
    readReceipts: List<ReadReceipt>,
    identityKeys: IdentityKeyPairMaterial?,
    isOnline: Boolean,
    isRecordingVoiceNote: Boolean,
    micLevel: Float,
    playingMessageId: String?,
    peerLookupState: PeerLookupState,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onSendImage: (ByteArray, String) -> Unit,
    onSendFile: (ByteArray, String, String) -> Unit,
    onStartVoiceNote: () -> Unit,
    onCancelVoiceNote: () -> Unit,
    onSendVoiceNote: () -> Unit,
    onTogglePlayVoiceNote: (DecryptedMessage) -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onLookupPeer: (String) -> Unit,
    onInvitePeerToGroup: (PublicIdentity) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var draftText by remember { mutableStateOf("") }
    var showSecuritySheet by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var previewImageMessage by remember { mutableStateOf<DecryptedMessage?>(null) }
    var localMediaError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val result = MediaUtils.readAndCompressImage(context, uri)
            if (result != null) {
                onSendImage(result.first, result.second)
            } else {
                localMediaError = "Could not compress selected image."
            }
        }
    }

    // SAF Document Picker (zero storage permission)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = MediaUtils.readSmallDocumentBytes(context, uri)
            if (result != null) {
                onSendFile(result.first, result.second, result.third)
            } else {
                localMediaError = "File exceeds 350KB E2EE envelope cap or cannot be read."
            }
        }
    }

    // Runtime Microphone Permission for Encrypted Voice Notes
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceNote()
        } else {
            localMediaError = "Microphone permission is required to record encrypted voice notes."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column(modifier = Modifier.clickable { showSecuritySheet = true }) {
                        Text(
                            text = conversation?.title ?: "Encrypted Chat",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOnline) {
                                    "AES-256-GCM · ${conversation?.memberUids?.size ?: 1} member(s)"
                                } else {
                                    "Offline Vault Mode · E2EE Active"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                actions = {
                    if (conversation?.type == "group") {
                        IconButton(onClick = { showInviteDialog = true }) {
                            Icon(Icons.Filled.GroupAdd, contentDescription = "Add member")
                        }
                    }
                    IconButton(
                        onClick = onStartAudioCall,
                        modifier = Modifier.testTag("start_audio_call_button")
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Start VoIP audio call")
                    }
                    IconButton(
                        onClick = onStartVideoCall,
                        modifier = Modifier.testTag("start_video_call_button")
                    ) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Start VoIP video call")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (localMediaError != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = localMediaError!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { localMediaError = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (isRecordingVoiceNote) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onCancelVoiceNote) {
                                Icon(Icons.Filled.Delete, contentDescription = "Discard voice note", tint = MaterialTheme.colorScheme.error)
                            }
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Recording E2EE Voice Note...", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { micLevel.coerceIn(0.05f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onSendVoiceNote,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send voice note",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("attach_photo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = "Send encrypted photo"
                                )
                            }

                            IconButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.testTag("attach_file_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Send encrypted file"
                                )
                            }

                            OutlinedTextField(
                                value = draftText,
                                onValueChange = { draftText = it },
                                placeholder = {
                                    Text(if (isOnline) "Encrypted message..." else "Queue encrypted offline...")
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("message_composer_input")
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            if (draftText.isBlank()) {
                                IconButton(
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) onStartVoiceNote()
                                        else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .testTag("record_voice_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = "Record encrypted voice note",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val msg = draftText
                                        draftText = ""
                                        onSendText(msg)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .testTag("send_message_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send encrypted message",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "End-to-End Encrypted Channel",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Messages, photos, voice notes, and files are encrypted with AES-256-GCM before leaving your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages, key = { it.clientMessageId }) { msg ->
                        MessageBubble(
                            msg = msg,
                            isGroup = conversation?.type == "group",
                            isPlayingAudio = playingMessageId == msg.id,
                            onImageClick = { previewImageMessage = msg },
                            onTogglePlayAudio = { onTogglePlayVoiceNote(msg) }
                        )
                    }
                }
            }
        }
    }

    if (showSecuritySheet) {
        AlertDialog(
            onDismissRequest = { showSecuritySheet = false },
            title = { Text("E2EE Channel Security") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Cipher Suite: ECDH P-256 Key Wrap + AES-256-GCM (96-bit random IV)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Your Identity Fingerprint:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = identityKeys?.fingerprint ?: "Unavailable",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (readReceipts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Member Read Cursors:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        readReceipts.forEach { rr ->
                            val status = if (!rr.readReceiptsEnabled) "Receipts disabled"
                            else if (rr.lastReadAtMs > 0L) "Read at ${MediaUtils.formatShortTime(rr.lastReadAtMs)}"
                            else "Delivered"
                            Text("${rr.displayName}: $status", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSecuritySheet = false }) { Text("Done") }
            }
        )
    }

    if (previewImageMessage?.mediaBytes != null) {
        val bmp = remember(previewImageMessage!!.id) {
            val bytes = previewImageMessage!!.mediaBytes!!
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        AlertDialog(
            onDismissRequest = { previewImageMessage = null },
            title = { Text(previewImageMessage?.mediaName ?: "Encrypted Photo") },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Decrypted photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Unable to decode decrypted image.")
                }
            },
            confirmButton = {
                TextButton(onClick = { previewImageMessage = null }) { Text("Close") }
            }
        )
    }

    if (showInviteDialog) {
        var peerQuery by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Add Member to Group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = peerQuery,
                        onValueChange = { peerQuery = it },
                        label = { Text("Peer @handle or UID") },
                        trailingIcon = {
                            IconButton(onClick = { onLookupPeer(peerQuery) }) {
                                Icon(Icons.Filled.Search, contentDescription = "Lookup")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    when (peerLookupState) {
                        is PeerLookupState.Found -> {
                            val p = peerLookupState.peer
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text("@${p.handle}", style = MaterialTheme.typography.labelSmall)
                                }
                                Button(onClick = {
                                    onInvitePeerToGroup(p)
                                    showInviteDialog = false
                                }) {
                                    Text("Wrap Key & Add")
                                }
                            }
                        }
                        is PeerLookupState.NotFound -> Text("User not found.", color = MaterialTheme.colorScheme.error)
                        is PeerLookupState.Error -> Text(peerLookupState.message, color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MessageBubble(
    msg: DecryptedMessage,
    isGroup: Boolean,
    isPlayingAudio: Boolean,
    onImageClick: () -> Unit,
    onTogglePlayAudio: () -> Unit
) {
    val alignment = if (msg.isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (msg.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (msg.isMine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (msg.isMine) 18.dp else 4.dp,
                bottomEnd = if (msg.isMine) 4.dp else 18.dp
            ),
            modifier = Modifier
                .widthIn(max = 310.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    RoundedCornerShape(18.dp)
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (!msg.isMine && isGroup) {
                    Text(
                        text = msg.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                }

                when (msg.messageType) {
                    "image" -> {
                        val bmp = remember(msg.id, msg.mediaBytes?.size) {
                            msg.mediaBytes?.let { bytes ->
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Encrypted photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onImageClick),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                    "audio" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = onTogglePlayAudio,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = if (isPlayingAudio) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlayingAudio) "Stop voice note" else "Play voice note",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isPlayingAudio) "Playing E2EE Audio..." else "Encrypted Voice Note",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor
                                )
                                Text(
                                    text = MediaUtils.formatDuration((msg.mediaDurationMs ?: 1000L) / 1000L),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                    "file" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = msg.mediaName ?: "Encrypted File",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor
                                )
                                Text(
                                    text = "${(msg.mediaBytes?.size ?: 0) / 1024} KB · AES-256-GCM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = MediaUtils.formatShortTime(msg.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    if (msg.isMine) {
                        Spacer(modifier = Modifier.width(5.dp))
                        when (msg.receiptState) {
                            DeliveryReceiptState.PENDING_OFFLINE -> {
                                Icon(
                                    imageVector = Icons.Filled.Schedule,
                                    contentDescription = "Queued offline",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            DeliveryReceiptState.FAILED_OFFLINE -> {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Retry pending",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            DeliveryReceiptState.SENT -> {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = "Sent",
                                    tint = textColor.copy(alpha = 0.65f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            DeliveryReceiptState.DELIVERED -> {
                                Icon(
                                    imageVector = Icons.Filled.DoneAll,
                                    contentDescription = "Delivered",
                                    tint = textColor.copy(alpha = 0.65f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            DeliveryReceiptState.READ -> {
                                Icon(
                                    imageVector = Icons.Filled.DoneAll,
                                    contentDescription = "Read",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                if (isGroup && msg.readByNames.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Read by ${msg.readByNames.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
