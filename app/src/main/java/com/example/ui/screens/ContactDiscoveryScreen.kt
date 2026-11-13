package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ContactEntry
import com.example.data.model.PublicIdentity
import com.example.sync.ContactSyncRuntimeState
import com.example.ui.components.UserAvatar
import com.example.util.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDiscoveryScreen(
    contacts: List<ContactEntry>,
    hasContactsPermission: Boolean,
    autoSyncEnabled: Boolean,
    syncStatus: ContactSyncRuntimeState,
    lastSavedSyncMs: Long,
    lastScannedContactsCount: Int,
    isOnline: Boolean,
    onBack: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onStartChat: (PublicIdentity) -> Unit,
    onStartVoiceCall: (PublicIdentity) -> Unit,
    onStartVideoCall: (PublicIdentity) -> Unit,
    onOpenProfile: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var showDisclosureDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult(granted)
    }

    val discoveredContacts = remember(contacts) {
        contacts.filter { it.discoverySource == "device_contacts" }
    }
    val manualContacts = remember(contacts) {
        contacts.filter { it.discoverySource != "device_contacts" }
    }

    val effectiveSyncTimeMs = maxOf(syncStatus.lastSyncAtMs, lastSavedSyncMs)
    val effectiveScannedCount = maxOf(syncStatus.lastScannedContactsCount, lastScannedContactsCount)

    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("contact_discovery_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.contact_sync_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = when {
                                syncStatus.isSyncing -> "ContactSyncService · Matching SHA-256 hashes..."
                                !hasContactsPermission -> "Permission required for address book discovery"
                                autoSyncEnabled -> "Background Contact Service Active"
                                else -> "On-demand contact sync ready"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 16.dp)
                    .testTag("contact_discovery_list"),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Service Status & Controls Hero Card
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                RoundedCornerShape(22.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colors = listOf(primaryGlow, secondaryGlow, Color.Transparent),
                                            center = Offset(size.width * 0.2f, 0f),
                                            radius = size.width * 0.85f
                                        )
                                    )
                                }
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.PersonSearch,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Device Contacts Sync",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = if (effectiveSyncTimeMs > 0L) {
                                                "Last synced ${MediaUtils.formatShortTime(effectiveSyncTimeMs)} · $effectiveScannedCount scanned · ${discoveredContacts.size} on UCN"
                                            } else {
                                                "Automatically discover friends already on UCN"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Privacy Shield Callout
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Emails & phone numbers are SHA-256 hashed on your device before matching Firestore. Raw contacts never leave your phone.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!hasContactsPermission) {
                                Button(
                                    onClick = { showDisclosureDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("grant_contacts_permission_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Contacts,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.contact_sync_grant_cta))
                                }
                                OutlinedButton(
                                    onClick = {
                                        val settingsIntent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        )
                                        context.startActivity(settingsIntent)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open App Permission Settings")
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = stringResource(R.string.contact_sync_auto_title),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = stringResource(R.string.contact_sync_auto_subtitle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = autoSyncEnabled,
                                        onCheckedChange = onToggleAutoSync,
                                        modifier = Modifier.testTag("contact_auto_sync_switch")
                                    )
                                }

                                Button(
                                    onClick = onSyncNow,
                                    enabled = !syncStatus.isSyncing && isOnline,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("sync_contacts_now_button")
                                ) {
                                    if (syncStatus.isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Syncing with Firestore...")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Sync,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.contact_sync_now))
                                    }
                                }
                            }

                            AnimatedVisibility(visible = !syncStatus.lastError.isNullOrBlank()) {
                                Text(
                                    text = syncStatus.lastError.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            TextButton(
                                onClick = onOpenProfile,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .testTag("contact_discovery_edit_phone_button")
                            ) {
                                Text("Add your phone number in Profile so friends find you")
                            }
                        }
                    }
                }

                // Discovered UCN Friends Section
                item {
                    Text(
                        text = "Discovered Friends on UCN (${discoveredContacts.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (discoveredContacts.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Contacts,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = stringResource(R.string.contact_sync_empty_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.contact_sync_empty_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(discoveredContacts, key = { "disc_${it.peerUserId}" }) { contact ->
                        DiscoveredFriendCard(
                            contact = contact,
                            onChat = { onStartChat(contact.toPublicIdentity()) },
                            onVoiceCall = { onStartVoiceCall(contact.toPublicIdentity()) },
                            onVideoCall = { onStartVideoCall(contact.toPublicIdentity()) }
                        )
                    }
                }

                // Manual / Saved Peer Contacts Section
                if (manualContacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "Added Manually (${manualContacts.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(manualContacts, key = { "man_${it.peerUserId}" }) { contact ->
                        DiscoveredFriendCard(
                            contact = contact,
                            onChat = { onStartChat(contact.toPublicIdentity()) },
                            onVoiceCall = { onStartVoiceCall(contact.toPublicIdentity()) },
                            onVideoCall = { onStartVideoCall(contact.toPublicIdentity()) }
                        )
                    }
                }
            }
        }
    }

    if (showDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showDisclosureDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(stringResource(R.string.contact_sync_disclosure_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.contact_sync_disclosure_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisclosureDialog = false
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    },
                    modifier = Modifier.testTag("confirm_contact_disclosure_button")
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosureDialog = false }) {
                    Text("Not Now")
                }
            }
        )
    }
}

@Composable
private fun DiscoveredFriendCard(
    contact: ContactEntry,
    onChat: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .testTag("discovered_friend_card_${contact.peerUserId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                displayName = contact.peerDisplayName,
                photoUrl = contact.peerPhotoUrl,
                avatarBase64 = contact.peerAvatarBase64,
                size = 50.dp,
                showCryptoRing = true
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.peerDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (contact.verified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = "Verified UCN Peer",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    text = "@${contact.peerHandle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val originLabel = buildString {
                    if (!contact.deviceContactName.isNullOrBlank()) {
                        append("Contact: ${contact.deviceContactName}")
                    } else {
                        append("Saved UCN peer")
                    }
                    if (!contact.matchedVia.isNullOrBlank()) {
                        append(" · via ${contact.matchedVia}")
                    }
                }
                Text(
                    text = originLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = contact.peerKeyFingerprint.take(19),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = onChat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("friend_chat_button_${contact.peerUserId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChatBubble,
                        contentDescription = "Message ${contact.peerDisplayName}"
                    )
                }
                FilledTonalIconButton(
                    onClick = onVoiceCall,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("friend_voice_call_button_${contact.peerUserId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Voice call ${contact.peerDisplayName}"
                    )
                }
                FilledTonalIconButton(
                    onClick = onVideoCall,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("friend_video_call_button_${contact.peerUserId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = "Video call ${contact.peerDisplayName}"
                    )
                }
            }
        }
    }
}

private fun ContactEntry.toPublicIdentity(): PublicIdentity = PublicIdentity(
    userId = peerUserId,
    displayName = peerDisplayName,
    handle = peerHandle,
    publicKeyBase64 = peerPublicKeyBase64,
    keyFingerprint = peerKeyFingerprint,
    photoUrl = peerPhotoUrl,
    avatarBase64 = peerAvatarBase64
)
