package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.CallParticipant
import com.example.ui.ActiveCallUiState
import com.example.util.MediaUtils
import com.example.voip.VoipDspCapabilities

@Composable
fun ActiveCallScreen(
    callState: ActiveCallUiState,
    currentUserId: String,
    micLevel: Float,
    dspCapabilities: VoipDspCapabilities,
    onToggleMute: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onFlipCamera: () -> Unit,
    onSwitchCodec: (String) -> Unit,
    onEndCall: () -> Unit
) {
    BackHandler(onBack = onEndCall)

    val context = LocalContext.current
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    var hasAudioPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasAudioPerm = result[Manifest.permission.RECORD_AUDIO] ?: hasAudioPerm
        hasCameraPerm = result[Manifest.permission.CAMERA] ?: hasCameraPerm
    }

    LaunchedEffect(callState.call.id, callState.isVideoEnabled) {
        val needed = buildList {
            if (!hasAudioPerm) add(Manifest.permission.RECORD_AUDIO)
            if (callState.isVideoEnabled && !hasCameraPerm) add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF070B11),
                        Color(0xFF0F1824),
                        Color(0xFF070B11)
                    )
                )
            )
            .systemBarsPadding()
    ) {
        // Real CameraX local preview when video & camera permission are enabled
        if (callState.isVideoEnabled && hasCameraPerm) {
            CameraXPreviewBox(
                useFrontCamera = callState.useFrontCamera,
                modifier = Modifier.fillMaxSize()
            )
            // Subtle dark scrim for control legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top VoIP Security & Telemetry Bar
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF102A2E).copy(alpha = 0.85f),
                        shape = RoundedCornerShape(100.dp),
                        modifier = Modifier.border(1.dp, Color(0xFF00E5C3).copy(alpha = 0.5f), RoundedCornerShape(100.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "DTLS-SRTP Verified",
                                tint = Color(0xFF00E5C3),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SAS ${callState.call.sasCode}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF00E5C3)
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF172230).copy(alpha = 0.88f),
                        shape = RoundedCornerShape(100.dp),
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier.testTag("voip_diagnostics_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NetworkCheck,
                                contentDescription = "VoIP diagnostics",
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${callState.rttMs}ms · ${callState.bitrateKbps}kbps",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = callState.call.conversationTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${if (callState.call.isGroup) "Group " else ""}${callState.call.callType.uppercase()} VoIP · ${MediaUtils.formatDuration(callState.elapsedSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF00E5C3)
                )
            }

            // Center Participant Stage (1:1 or Multi-Party Group Grid)
            val displayParticipants = if (callState.participants.isNotEmpty()) {
                callState.participants
            } else {
                listOf(
                    CallParticipant(
                        userId = currentUserId,
                        displayName = "You (Encrypted)",
                        audioEnabled = !callState.isMuted,
                        videoEnabled = callState.isVideoEnabled,
                        networkQuality = callState.networkQuality,
                        rttMs = callState.rttMs
                    )
                )
            }

            if (!callState.isVideoEnabled || displayParticipants.size > 1) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (displayParticipants.size > 1) 2 else 1),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 20.dp)
                ) {
                    items(displayParticipants, key = { it.userId }) { p ->
                        ParticipantStageTile(
                            participant = p,
                            isLocal = p.userId == currentUserId,
                            localMicLevel = micLevel
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Bottom VoIP Control Dock
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // Live Voice Activity & Hardware DSP Meter
                Surface(
                    color = Color(0xFF131B24).copy(alpha = 0.85f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = Color(0xFF00E5C3),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dspCapabilities.audioModeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { if (callState.isMuted) 0f else micLevel.coerceIn(0.05f, 1f) },
                                color = Color(0xFF00E5C3),
                                trackColor = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Surface(
                    color = Color(0xFF131B24).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallDockButton(
                            icon = if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (callState.isMuted) "Unmute" else "Mute",
                            active = callState.isMuted,
                            onClick = onToggleMute,
                            tag = "call_mute_button"
                        )
                        CallDockButton(
                            icon = if (callState.isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            label = if (callState.isVideoEnabled) "Video On" else "Video Off",
                            active = callState.isVideoEnabled,
                            onClick = onToggleVideo,
                            tag = "call_video_button"
                        )
                        if (callState.isVideoEnabled) {
                            CallDockButton(
                                icon = Icons.Filled.Cameraswitch,
                                label = "Flip",
                                active = false,
                                onClick = onFlipCamera,
                                tag = "call_flip_camera_button"
                            )
                        } else {
                            CallDockButton(
                                icon = if (callState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Hearing,
                                label = if (callState.isSpeakerOn) "Speaker" else "Earpiece",
                                active = callState.isSpeakerOn,
                                onClick = onToggleSpeaker,
                                tag = "call_speaker_button"
                            )
                        }
                        IconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                                .testTag("call_end_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = "End call",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("VoIP Protocol & Quality") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ICE Transport: ${callState.participants.firstOrNull()?.iceState ?: "connected"}")
                    Text("RTT Latency: ${callState.rttMs} ms · Jitter: ${callState.jitterMs} ms")
                    Text("Bitrate: ${callState.bitrateKbps} kbps · Loss: ${callState.packetLossTenthsPct / 10.0}%")
                    Text("DSP: AEC=${dspCapabilities.aecActive}, NS=${dspCapabilities.nsActive}, AGC=${dspCapabilities.agcActive}")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Select Adaptive Codec Profile:", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = callState.call.codecProfile == "opus_48k_vp9_hd",
                        onClick = { onSwitchCodec("opus_48k_vp9_hd") },
                        label = { Text("Opus 48kHz + VP9 HD") }
                    )
                    FilterChip(
                        selected = callState.call.codecProfile == "opus_24k_vp8_balanced",
                        onClick = { onSwitchCodec("opus_24k_vp8_balanced") },
                        label = { Text("Opus 24kHz + VP8 Balanced") }
                    )
                    FilterChip(
                        selected = callState.call.codecProfile == "opus_12k_fec_low_bw",
                        onClick = { onSwitchCodec("opus_12k_fec_low_bw") },
                        label = { Text("Opus 12kHz + FEC Low-Bandwidth") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ParticipantStageTile(
    participant: CallParticipant,
    isLocal: Boolean,
    localMicLevel: Float
) {
    Surface(
        color = Color(0xFF131B24).copy(alpha = 0.85f),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .border(
                1.5.dp,
                if (isLocal && localMicLevel > 0.12f && participant.audioEnabled) Color(0xFF00E5C3)
                else Color.White.copy(alpha = 0.14f),
                RoundedCornerShape(22.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B2633)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = participant.displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5C3)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = participant.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${if (participant.audioEnabled) "Mic Live" else "Muted"} · ${participant.rttMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9FB0C4)
            )
        }
    }
}

@Composable
private fun CallDockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF00E5C3) else Color(0xFF1F2B38))
                .testTag(tag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color(0xFF00251F) else Color.White
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun CameraXPreviewBox(
    useFrontCamera: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(useFrontCamera, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
            } catch (_: Exception) {
                // Gracefully handle devices without front/back camera hardware
            }
        }, executor)

        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}
