package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.model.SyncedDevice
import com.example.data.model.UserProfile
import com.example.data.preferences.LocalAppPreferences
import com.example.sync.ContactSyncRuntimeState
import com.example.ui.components.UserAvatar
import com.example.ui.theme.BrandLogoCanvas
import com.example.util.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSyncScreen(
    userProfile: UserProfile?,
    identityKeys: IdentityKeyPairMaterial?,
    preferences: LocalAppPreferences,
    syncedDevices: List<SyncedDevice>,
    currentDeviceId: String,
    isOnline: Boolean,
    transportLabel: String,
    pendingOutboxCount: Int,
    lastCloudSyncMs: Long,
    onSetDarkMode: (String) -> Unit,
    onSetHighContrast: (Boolean) -> Unit,
    onSetReadReceipts: (Boolean) -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onSetOfflineSim: (Boolean) -> Unit,
    onSetCodecProfile: (String) -> Unit,
    onManualSync: () -> Unit,
    onRotateKeys: () -> Unit,
    onRemoveDevice: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onSignOut: () -> Unit,
    hasContactsPermission: Boolean = false,
    contactSyncStatus: ContactSyncRuntimeState = ContactSyncRuntimeState(),
    discoveredFriendsCount: Int = 0,
    onSetContactAutoSync: (Boolean) -> Unit = {},
    onOpenContactDiscovery: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Security & Cloud Sync", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = if (isOnline) "Connected ($transportLabel)" else "Offline Storage Mode",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Identity & E2EE Keyring Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(
                                displayName = userProfile?.displayName ?: "UCN User",
                                photoUrl = userProfile?.photoUrl,
                                avatarBase64 = userProfile?.avatarBase64,
                                size = 52.dp,
                                showCryptoRing = true
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userProfile?.displayName ?: "UCN User",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "@${userProfile?.handle ?: "syncing"} · ${userProfile?.email.orEmpty()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (userProfile != null) {
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(userProfile.handle))
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy handle")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FilledTonalButton(
                            onClick = onOpenProfile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit Profile Name & Photo")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Filled.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("ECDH P-256 Public Key Fingerprint", style = MaterialTheme.typography.labelSmall)
                                        Text(
                                            text = identityKeys?.fingerprint ?: "Provisioning...",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = onRotateKeys,
                                    modifier = Modifier.testTag("rotate_keys_button")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Rotate")
                                }
                            }
                        }
                    }
                }
            }

            // Dark Mode & Accessibility
            item {
                SectionCard(
                    icon = Icons.Filled.Brightness6,
                    title = "Appearance & Accessibility"
                ) {
                    Text(
                        text = "Theme Mode (synced across devices)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = preferences.darkMode == "dark",
                            onClick = { onSetDarkMode("dark") },
                            label = { Text("Dark") },
                            modifier = Modifier.testTag("theme_dark_chip")
                        )
                        FilterChip(
                            selected = preferences.darkMode == "light",
                            onClick = { onSetDarkMode("light") },
                            label = { Text("Light") },
                            modifier = Modifier.testTag("theme_light_chip")
                        )
                        FilterChip(
                            selected = preferences.darkMode == "system",
                            onClick = { onSetDarkMode("system") },
                            label = { Text("System") },
                            modifier = Modifier.testTag("theme_system_chip")
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    ToggleRow(
                        title = "High-Contrast Accessibility",
                        subtitle = "Boost outline contrast & text legibility",
                        checked = preferences.highContrastAccessibility,
                        onCheckedChange = onSetHighContrast,
                        tag = "high_contrast_switch"
                    )
                }
            }

            // Read Receipts & Privacy
            item {
                SectionCard(
                    icon = Icons.Filled.DoneAll,
                    title = "Privacy & Read Receipts"
                ) {
                    ToggleRow(
                        title = "Send Read Receipts",
                        subtitle = "Notify peers & groups when you view messages",
                        checked = preferences.readReceiptsEnabled,
                        onCheckedChange = onSetReadReceipts,
                        tag = "read_receipts_switch"
                    )
                }
            }

            // Device Contacts Discovery Service
            item {
                SectionCard(
                    icon = Icons.Filled.PersonSearch,
                    title = "Friend Discovery Service"
                ) {
                    val syncDesc = when {
                        contactSyncStatus.isSyncing -> "ContactSyncService active · checking hashes..."
                        !hasContactsPermission -> "Grant Contacts access to match friends on UCN"
                        else -> "$discoveredFriendsCount friend(s) discovered · ${preferences.lastScannedContactsCount} contacts scanned"
                    }
                    Text(
                        text = syncDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (hasContactsPermission) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ToggleRow(
                            title = "Auto-Sync When Contacts Change",
                            subtitle = "Monitors address book updates using SHA-256 hashes",
                            checked = preferences.contactAutoSyncEnabled,
                            onCheckedChange = onSetContactAutoSync,
                            tag = "settings_contact_auto_sync_switch"
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = onOpenContactDiscovery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_open_contact_sync_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PersonSearch,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Friend Discovery & Sync")
                    }
                }
            }

            // Offline Storage & Cloud Sync
            item {
                SectionCard(
                    icon = Icons.Filled.CloudSync,
                    title = "Cloud Sync & Offline Vault"
                ) {
                    ToggleRow(
                        title = "Auto Multi-Device Cloud Sync",
                        subtitle = "Sync keyring, preferences & chats in real time",
                        checked = preferences.autoSyncEnabled,
                        onCheckedChange = onSetAutoSync,
                        tag = "auto_sync_switch"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        title = "Simulate Limited Connectivity",
                        subtitle = "Queue new messages in Room offline vault (${pendingOutboxCount} pending)",
                        checked = preferences.offlineSimulationMode,
                        onCheckedChange = onSetOfflineSim,
                        tag = "offline_sim_switch"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (lastCloudSyncMs > 0L) "Last synced: ${MediaUtils.formatShortTime(lastCloudSyncMs)}" else "Ready to sync",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Offline Outbox: $pendingOutboxCount queued",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(
                            onClick = onManualSync,
                            modifier = Modifier.testTag("manual_sync_button")
                        ) {
                            Icon(
                                imageVector = if (isOnline) Icons.Filled.CloudSync else Icons.Filled.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now")
                        }
                    }
                }
            }

            // VoIP Codec Preferences
            item {
                SectionCard(
                    icon = Icons.Filled.NetworkCheck,
                    title = "VoIP Protocol Quality"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = preferences.voipCodecProfile == "opus_48k_vp9_hd",
                            onClick = { onSetCodecProfile("opus_48k_vp9_hd") },
                            label = { Text("Opus 48kHz + VP9 HD (Wi-Fi / 5G)") }
                        )
                        FilterChip(
                            selected = preferences.voipCodecProfile == "opus_24k_vp8_balanced",
                            onClick = { onSetCodecProfile("opus_24k_vp8_balanced") },
                            label = { Text("Opus 24kHz + VP8 Balanced (LTE)") }
                        )
                        FilterChip(
                            selected = preferences.voipCodecProfile == "opus_12k_fec_low_bw",
                            onClick = { onSetCodecProfile("opus_12k_fec_low_bw") },
                            label = { Text("Opus 12kHz + FEC Low-Bandwidth") }
                        )
                    }
                }
            }

            // Linked Cloud Devices
            item {
                Text(
                    text = "Linked Devices (${syncedDevices.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(syncedDevices, key = { it.id }) { dev ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dev.deviceName + if (dev.id == currentDeviceId) " (This Device)" else "",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${dev.platform} · Key ${dev.keyFingerprint.take(14)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (dev.id != currentDeviceId) {
                            IconButton(onClick = { onRemoveDevice(dev.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "Remove device",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Sign Out
            item {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onSignOut,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out")
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BrandLogoCanvas,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ucn_logo),
                            contentDescription = "UCN Official Logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(width = 110.dp, height = 56.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
