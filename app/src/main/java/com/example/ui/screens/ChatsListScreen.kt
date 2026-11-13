package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.model.ContactEntry
import com.example.data.model.DecryptedConversationSummary
import com.example.data.model.PublicIdentity
import com.example.data.model.UserProfile
import com.example.ui.PeerLookupState
import com.example.ui.components.ConversationSearchBar
import com.example.ui.components.UserAvatar
import com.example.ui.theme.BrandLogoCanvas
import com.example.util.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    conversations: List<DecryptedConversationSummary>,
    contacts: List<ContactEntry>,
    userProfile: UserProfile?,
    identityKeys: IdentityKeyPairMaterial?,
    isOnline: Boolean,
    transportLabel: String,
    pendingOutboxCount: Int,
    peerLookupState: PeerLookupState,
    onOpenConversation: (String) -> Unit,
    onLookupPeer: (String) -> Unit,
    onClearLookup: () -> Unit,
    onStartDirectChat: (PublicIdentity?, (String) -> Unit) -> Unit,
    onCreateGroupChat: (String, List<PublicIdentity>, (String) -> Unit) -> Unit,
    onSyncNow: () -> Unit,
    onOpenProfile: () -> Unit,
    hasContactsPermission: Boolean = false,
    onOpenContactDiscovery: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") } // all, direct, group
    var showNewChatDialog by remember { mutableStateOf(false) }
    var startAsGroup by remember { mutableStateOf(false) }

    val trimmedQuery = searchQuery.trim()
    val filtered = remember(conversations, trimmedQuery, selectedFilter) {
        conversations.filter { c ->
            val matchesFilter = when (selectedFilter) {
                "direct" -> c.type == "direct"
                "group" -> c.type == "group"
                else -> true
            }
            val matchesQuery = trimmedQuery.isEmpty() ||
                c.title.contains(trimmedQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BrandLogoCanvas,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 4.dp)
                            .size(38.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ucn_logo),
                            contentDescription = "UCN Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp)
                                .testTag("ucn_topbar_logo")
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "UCN Encrypted",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isOnline) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isOnline) {
                                    "Cloud Synced ($transportLabel)" + if (pendingOutboxCount > 0) " · Flushing $pendingOutboxCount" else ""
                                } else {
                                    "Offline Vault · $pendingOutboxCount queued"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSyncNow,
                        modifier = Modifier.testTag("sync_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = "Sync now"
                        )
                    }
                    IconButton(
                        onClick = onOpenContactDiscovery,
                        modifier = Modifier.testTag("chats_discover_friends_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PersonSearch,
                            contentDescription = "Discover friends from device contacts"
                        )
                    }
                    IconButton(
                        onClick = {
                            startAsGroup = true
                            onClearLookup()
                            showNewChatDialog = true
                        },
                        modifier = Modifier.testTag("new_group_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GroupAdd,
                            contentDescription = "New group chat"
                        )
                    }
                    IconButton(
                        onClick = onOpenProfile,
                        modifier = Modifier.testTag("chats_profile_avatar_button")
                    ) {
                        UserAvatar(
                            displayName = userProfile?.displayName ?: "UCN User",
                            photoUrl = userProfile?.photoUrl,
                            avatarBase64 = userProfile?.avatarBase64,
                            size = 34.dp,
                            showCryptoRing = true
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    startAsGroup = false
                    onClearLookup()
                    showNewChatDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("new_chat_fab")
            ) {
                Icon(Icons.Filled.AddComment, contentDescription = "New encrypted chat")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Conversation Name Search Bar
                ConversationSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    matchingCount = if (trimmedQuery.isNotEmpty()) filtered.size else null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

            // Filter chips + Quick Handle Share pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedFilter == "all",
                    onClick = { selectedFilter = "all" },
                    label = { Text("All (${conversations.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "direct",
                    onClick = { selectedFilter = "direct" },
                    label = { Text("1:1 Direct") }
                )
                FilterChip(
                    selected = selectedFilter == "group",
                    onClick = { selectedFilter = "group" },
                    label = { Text("Groups") }
                )
            }

            if (userProfile != null) {
                val clipboard = LocalClipboardManager.current
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable {
                            clipboard.setText(AnnotatedString(userProfile.handle))
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Your UCN Handle: @${userProfile.handle}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Key: ${identityKeys?.fingerprint?.take(19) ?: "Loading..."}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy handle",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            val discoveredCount = remember(contacts) {
                contacts.count { it.discoverySource == "device_contacts" }
            }
            Surface(
                onClick = onOpenContactDiscovery,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        RoundedCornerShape(14.dp)
                    )
                    .testTag("chats_discover_friends_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Filled.PersonSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when {
                                    discoveredCount > 0 -> "$discoveredCount Friend(s) Discovered on UCN"
                                    hasContactsPermission -> "Sync Device Contacts to Find Friends"
                                    else -> "Find Friends Already Using UCN"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Private SHA-256 contact matching · Tap to open",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = if (hasContactsPermission) "Sync" else "Open",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (filtered.isEmpty() && trimmedQuery.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp)
                        .testTag("chats_search_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.chats_search_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.chats_search_empty_body, trimmedQuery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.testTag("chats_empty_search_clear_button")
                        ) {
                            Text(stringResource(R.string.chats_search_clear_action))
                        }
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_ucn_empty_chats),
                            contentDescription = "No encrypted conversations",
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Zero-Knowledge Messaging Ready",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Invite a peer using their UCN @handle or create a group channel with per-member ECDH key wrapping.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                startAsGroup = false
                                onClearLookup()
                                showNewChatDialog = true
                            }) {
                                Text("New 1:1 Chat")
                            }
                            OutlinedButton(onClick = {
                                startAsGroup = true
                                onClearLookup()
                                showNewChatDialog = true
                            }) {
                                Text("New Group")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { conv ->
                        ConversationCard(
                            conv = conv,
                            onClick = { onOpenConversation(conv.id) }
                        )
                    }
                }
            }
            }
        }
    }

    if (showNewChatDialog) {
        NewConversationDialog(
            initialIsGroup = startAsGroup,
            contacts = contacts,
            peerLookupState = peerLookupState,
            onLookupPeer = onLookupPeer,
            onDismiss = {
                onClearLookup()
                showNewChatDialog = false
            },
            onCreateDirect = { peer ->
                showNewChatDialog = false
                onStartDirectChat(peer) { convId -> onOpenConversation(convId) }
            },
            onCreateGroup = { title, peers ->
                showNewChatDialog = false
                onCreateGroupChat(title, peers) { convId -> onOpenConversation(convId) }
            }
        )
    }
}

@Composable
private fun ConversationCard(
    conv: DecryptedConversationSummary,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("conversation_card_${conv.id}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        if (conv.type == "group") MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (conv.type == "group") {
                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = "Group chat",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Text(
                        text = conv.title.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            text = conv.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "End-to-end encrypted",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    if (conv.lastActivityMs > 0L) {
                        Text(
                            text = MediaUtils.formatShortTime(conv.lastActivityMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (conv.lastMessageSenderName.isNotBlank() && conv.type == "group") {
                            "${conv.lastMessageSenderName}: ${conv.lastPreviewText}"
                        } else {
                            conv.lastPreviewText
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (!conv.activeCallId.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Call,
                                    contentDescription = "Active call",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewConversationDialog(
    initialIsGroup: Boolean,
    contacts: List<ContactEntry>,
    peerLookupState: PeerLookupState,
    onLookupPeer: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreateDirect: (PublicIdentity?) -> Unit,
    onCreateGroup: (String, List<PublicIdentity>) -> Unit
) {
    var isGroup by remember { mutableStateOf(initialIsGroup) }
    var groupTitle by remember { mutableStateOf("") }
    var handleQuery by remember { mutableStateOf("") }
    val selectedPeers = remember { mutableStateListOf<PublicIdentity>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isGroup) "Create Encrypted Group" else "New Encrypted Chat",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isGroup,
                        onClick = { isGroup = false },
                        label = { Text("1:1 Direct / Vault") }
                    )
                    FilterChip(
                        selected = isGroup,
                        onClick = { isGroup = true },
                        label = { Text("Group Channel") }
                    )
                }

                if (isGroup) {
                    OutlinedTextField(
                        value = groupTitle,
                        onValueChange = { groupTitle = it },
                        label = { Text("Group Channel Name") },
                        placeholder = { Text("e.g. Ops Response Team") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("group_title_input")
                    )
                }

                OutlinedTextField(
                    value = handleQuery,
                    onValueChange = { handleQuery = it },
                    label = { Text("Peer UCN @handle or User ID") },
                    placeholder = { Text("e.g. alice_9a2f") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { onLookupPeer(handleQuery) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Lookup UCN peer")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("peer_lookup_input")
                )

                when (peerLookupState) {
                    PeerLookupState.Idle -> Unit
                    PeerLookupState.Searching -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verifying ECDH public key...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is PeerLookupState.Found -> {
                        val peer = peerLookupState.peer
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text("@${peer.handle}", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        "Key: ${peer.keyFingerprint.take(19)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (isGroup) {
                                    IconButton(onClick = {
                                        if (selectedPeers.none { it.userId == peer.userId }) {
                                            selectedPeers.add(peer)
                                        }
                                    }) {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = "Add to group")
                                    }
                                } else {
                                    Button(onClick = { onCreateDirect(peer) }) {
                                        Text("Chat")
                                    }
                                }
                            }
                        }
                    }
                    is PeerLookupState.NotFound -> {
                        Text(
                            text = "No UCN user found for \"${peerLookupState.query}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is PeerLookupState.Error -> {
                        Text(
                            text = peerLookupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (contacts.isNotEmpty()) {
                    Text(
                        text = "Saved Verified Contacts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    contacts.take(4).forEach { c ->
                        val peer = PublicIdentity(
                            userId = c.peerUserId,
                            displayName = c.peerDisplayName,
                            handle = c.peerHandle,
                            publicKeyBase64 = c.peerPublicKeyBase64,
                            keyFingerprint = c.peerKeyFingerprint,
                            photoUrl = c.peerPhotoUrl,
                            avatarBase64 = c.peerAvatarBase64
                        )
                        val alreadyAdded = selectedPeers.any { it.userId == c.peerUserId }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isGroup) {
                                        if (alreadyAdded) selectedPeers.removeAll { it.userId == c.peerUserId }
                                        else selectedPeers.add(peer)
                                    } else {
                                        onCreateDirect(peer)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(c.peerDisplayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = if (!c.deviceContactName.isNullOrBlank()) {
                                            "@${c.peerHandle} · Contact: ${c.deviceContactName}"
                                        } else {
                                            "@${c.peerHandle}"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (isGroup && alreadyAdded) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                if (isGroup && selectedPeers.isNotEmpty()) {
                    Text(
                        text = "Selected members: ${selectedPeers.joinToString { it.displayName }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (isGroup) {
                Button(
                    onClick = {
                        onCreateGroup(groupTitle.ifBlank { "Encrypted Group" }, selectedPeers.toList())
                    },
                    modifier = Modifier.testTag("confirm_create_group")
                ) {
                    Text("Create Group")
                }
            } else {
                OutlinedButton(
                    onClick = { onCreateDirect(null) },
                    modifier = Modifier.testTag("open_personal_vault_button")
                ) {
                    Text("Open Personal Vault")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
