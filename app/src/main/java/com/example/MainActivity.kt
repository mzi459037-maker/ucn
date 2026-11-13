package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.auth.GoogleAuthHelper
import com.example.data.model.VoipCall
import com.example.ui.UcnViewModel
import com.example.ui.screens.ActiveCallScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallsScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.ContactDiscoveryScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsSyncScreen
import com.example.ui.theme.UcnTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

const val ROUTE_CHATS = "chats"
const val ROUTE_CALLS = "calls"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_PROFILE = "profile"
const val ROUTE_CONTACT_SYNC = "contact_sync"
const val ROUTE_CHAT_DETAIL = "chat_detail/{conversationId}"

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UcnRootApp()
        }
    }
}

@Composable
fun UcnRootApp() {
    val auth = Firebase.auth
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            GoogleAuthHelper.attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        UcnTheme(darkModePreference = "dark") {
            AuthScreen(onAuthSuccess = {})
        }
    } else {
        key(user.uid) {
            UcnAuthenticatedShell(
                user = user,
                onSignOut = {
                    GoogleAuthHelper.signOut(credentialManager, scope)
                }
            )
        }
    }
}

@Composable
private fun UcnAuthenticatedShell(
    user: FirebaseUser,
    onSignOut: () -> Unit
) {
    val vm: UcnViewModel = viewModel(
        key = user.uid,
        factory = UcnViewModel.factory(
            uid = user.uid,
            email = user.email.orEmpty(),
            displayName = user.displayName.orEmpty(),
            photoUrl = user.photoUrl?.toString()
        )
    )

    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val publicIdentity by vm.publicIdentity.collectAsStateWithLifecycle()
    val isSavingProfile by vm.isSavingProfile.collectAsStateWithLifecycle()
    val identityKeys by vm.identityKeys.collectAsStateWithLifecycle()
    val syncedDevices by vm.syncedDevices.collectAsStateWithLifecycle()
    val deviceId by vm.deviceId.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val conversations by vm.conversationSummaries.collectAsStateWithLifecycle()
    val pendingOutbox by vm.pendingOutbox.collectAsStateWithLifecycle()
    val realNet by vm.realNetworkStatus.collectAsStateWithLifecycle()
    val isOnline by vm.isEffectivelyOnline.collectAsStateWithLifecycle()
    val peerLookupState by vm.peerLookupState.collectAsStateWithLifecycle()
    val activeConversation by vm.activeConversation.collectAsStateWithLifecycle()
    val activeMessages by vm.activeMessages.collectAsStateWithLifecycle()
    val activeReceipts by vm.activeReadReceipts.collectAsStateWithLifecycle()
    val isRecordingVoiceNote by vm.isRecordingVoiceNote.collectAsStateWithLifecycle()
    val micLevel by vm.micLevel.collectAsStateWithLifecycle()
    val dspCapabilities by vm.dspCapabilities.collectAsStateWithLifecycle()
    val playingMessageId by vm.playingMessageId.collectAsStateWithLifecycle()
    val callHistory by vm.callHistory.collectAsStateWithLifecycle()
    val incomingCall by vm.incomingRingingCall.collectAsStateWithLifecycle()
    val activeCallState by vm.activeCallState.collectAsStateWithLifecycle()
    val statusBanner by vm.statusBanner.collectAsStateWithLifecycle()
    val lastCloudSyncMs by vm.lastCloudSyncMs.collectAsStateWithLifecycle()
    val hasContactsPermission by vm.hasContactsPermission.collectAsStateWithLifecycle()
    val contactSyncStatus by vm.contactSyncStatus.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshContactsPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(ROUTE_CHATS, ROUTE_CALLS, ROUTE_SETTINGS, ROUTE_PROFILE) && activeCallState == null

    val navigateToTab: (String) -> Unit = { targetRoute ->
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearStatusBanner()
        }
    }

    UcnTheme(
        darkModePreference = preferences.darkMode,
        highContrast = preferences.highContrastAccessibility
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_CHATS,
                                onClick = {
                                    navController.navigate(ROUTE_CHATS) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_CHATS) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = "Chats"
                                    )
                                },
                                label = { Text("Chats") },
                                modifier = Modifier.testTag("nav_chats_tab")
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_CALLS,
                                onClick = {
                                    navController.navigate(ROUTE_CALLS) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_CALLS) Icons.Filled.Call else Icons.Outlined.Call,
                                        contentDescription = "Calls"
                                    )
                                },
                                label = { Text("VoIP") },
                                modifier = Modifier.testTag("nav_calls_tab")
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_SETTINGS,
                                onClick = { navigateToTab(ROUTE_SETTINGS) },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_SETTINGS) Icons.Filled.Shield else Icons.Outlined.Shield,
                                        contentDescription = "Security and Sync"
                                    )
                                },
                                label = { Text("Sync & Key") },
                                modifier = Modifier.testTag("nav_settings_tab")
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_PROFILE,
                                onClick = { navigateToTab(ROUTE_PROFILE) },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_PROFILE) Icons.Filled.Person else Icons.Outlined.Person,
                                        contentDescription = "User Profile"
                                    )
                                },
                                label = { Text("Profile") },
                                modifier = Modifier.testTag("nav_profile_tab")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_CHATS,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable(ROUTE_CHATS) {
                        ChatsListScreen(
                            conversations = conversations,
                            contacts = contacts,
                            userProfile = userProfile,
                            identityKeys = identityKeys,
                            isOnline = isOnline,
                            transportLabel = realNet.transportLabel,
                            pendingOutboxCount = pendingOutbox.size,
                            peerLookupState = peerLookupState,
                            onOpenConversation = { convId ->
                                navController.navigate("chat_detail/$convId")
                            },
                            onLookupPeer = vm::lookupPeer,
                            onClearLookup = vm::clearPeerLookup,
                            onStartDirectChat = vm::startDirectChat,
                            onCreateGroupChat = vm::createGroupChat,
                            onSyncNow = vm::triggerManualCloudSync,
                            onOpenProfile = { navigateToTab(ROUTE_PROFILE) },
                            hasContactsPermission = hasContactsPermission,
                            onOpenContactDiscovery = { navController.navigate(ROUTE_CONTACT_SYNC) }
                        )
                    }

                    composable(ROUTE_CALLS) {
                        CallsScreen(
                            callHistory = callHistory,
                            conversations = conversations,
                            codecProfile = preferences.voipCodecProfile,
                            transportLabel = realNet.transportLabel,
                            isOnline = isOnline,
                            onStartCall = vm::startCall,
                            onJoinCall = vm::joinCall
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsSyncScreen(
                            userProfile = userProfile,
                            identityKeys = identityKeys,
                            preferences = preferences,
                            syncedDevices = syncedDevices,
                            currentDeviceId = deviceId,
                            isOnline = isOnline,
                            transportLabel = realNet.transportLabel,
                            pendingOutboxCount = pendingOutbox.size,
                            lastCloudSyncMs = lastCloudSyncMs,
                            onSetDarkMode = vm::setDarkMode,
                            onSetHighContrast = vm::setHighContrast,
                            onSetReadReceipts = vm::setReadReceiptsEnabled,
                            onSetAutoSync = vm::setAutoSyncEnabled,
                            onSetOfflineSim = vm::setOfflineSimulationMode,
                            onSetCodecProfile = vm::setVoipCodecProfile,
                            onManualSync = vm::triggerManualCloudSync,
                            onRotateKeys = vm::rotateIdentityKeys,
                            onRemoveDevice = vm::removeSyncedDevice,
                            onOpenProfile = { navigateToTab(ROUTE_PROFILE) },
                            onSignOut = onSignOut,
                            hasContactsPermission = hasContactsPermission,
                            contactSyncStatus = contactSyncStatus,
                            discoveredFriendsCount = contacts.count { it.discoverySource == "device_contacts" },
                            onSetContactAutoSync = vm::setContactAutoSyncEnabled,
                            onOpenContactDiscovery = { navController.navigate(ROUTE_CONTACT_SYNC) }
                        )
                    }

                    composable(ROUTE_PROFILE) {
                        ProfileScreen(
                            userProfile = userProfile,
                            publicIdentity = publicIdentity,
                            identityKeys = identityKeys,
                            isOnline = isOnline,
                            isSaving = isSavingProfile,
                            onSaveProfile = vm::updateProfile
                        )
                    }

                    composable(ROUTE_CONTACT_SYNC) {
                        ContactDiscoveryScreen(
                            contacts = contacts,
                            hasContactsPermission = hasContactsPermission,
                            autoSyncEnabled = preferences.contactAutoSyncEnabled,
                            syncStatus = contactSyncStatus,
                            lastSavedSyncMs = preferences.lastContactSyncMs,
                            lastScannedContactsCount = preferences.lastScannedContactsCount,
                            isOnline = isOnline,
                            onBack = { navController.popBackStack() },
                            onPermissionResult = vm::onContactsPermissionResult,
                            onToggleAutoSync = vm::setContactAutoSyncEnabled,
                            onSyncNow = vm::triggerManualContactSync,
                            onStartChat = { peer ->
                                vm.startDirectChat(peer) { convId ->
                                    navController.navigate("chat_detail/$convId")
                                }
                            },
                            onStartVoiceCall = { peer ->
                                vm.startDirectCallWithPeer(peer, "audio")
                            },
                            onStartVideoCall = { peer ->
                                vm.startDirectCallWithPeer(peer, "video")
                            },
                            onOpenProfile = { navigateToTab(ROUTE_PROFILE) }
                        )
                    }

                    composable(ROUTE_CHAT_DETAIL) { entry ->
                        val convId = entry.arguments?.getString("conversationId").orEmpty()
                        DisposableEffect(convId) {
                            if (convId.isNotBlank()) {
                                vm.openConversation(convId)
                            }
                            onDispose {
                                vm.closeConversation()
                            }
                        }

                        ChatDetailScreen(
                            conversation = activeConversation,
                            messages = activeMessages,
                            readReceipts = activeReceipts,
                            identityKeys = identityKeys,
                            isOnline = isOnline,
                            isRecordingVoiceNote = isRecordingVoiceNote,
                            micLevel = micLevel,
                            playingMessageId = playingMessageId,
                            peerLookupState = peerLookupState,
                            onBack = { navController.popBackStack() },
                            onSendText = vm::sendTextMessage,
                            onSendImage = vm::sendEncryptedImage,
                            onSendFile = vm::sendEncryptedFile,
                            onStartVoiceNote = vm::startVoiceNoteRecording,
                            onCancelVoiceNote = vm::cancelVoiceNoteRecording,
                            onSendVoiceNote = vm::stopAndSendVoiceNote,
                            onTogglePlayVoiceNote = vm::togglePlayVoiceNote,
                            onStartAudioCall = { vm.startCall(convId, "audio") },
                            onStartVideoCall = { vm.startCall(convId, "video") },
                            onLookupPeer = vm::lookupPeer,
                            onInvitePeerToGroup = vm::invitePeerToActiveGroup
                        )
                    }
                }
            }

            // Incoming VoIP Ringing Banner
            AnimatedVisibility(
                visible = incomingCall != null && activeCallState == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(14.dp)
            ) {
                incomingCall?.let { call ->
                    IncomingCallBanner(
                        call = call,
                        onAccept = { vm.joinCall(call) }
                    )
                }
            }

            // Full-Screen Active VoIP Call Overlay
            AnimatedVisibility(
                visible = activeCallState != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                activeCallState?.let { callState ->
                    ActiveCallScreen(
                        callState = callState,
                        currentUserId = user.uid,
                        micLevel = micLevel,
                        dspCapabilities = dspCapabilities,
                        onToggleMute = vm::toggleCallMute,
                        onToggleVideo = vm::toggleCallVideo,
                        onToggleSpeaker = vm::toggleCallSpeaker,
                        onFlipCamera = vm::flipCallCamera,
                        onSwitchCodec = vm::switchActiveCallCodec,
                        onEndCall = vm::endActiveCall
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingCallBanner(
    call: VoipCall,
    onAccept: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (call.callType == "video") Icons.Filled.Videocam else Icons.Filled.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = call.conversationTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Incoming ${if (call.isGroup) "Group " else ""}${call.callType.uppercase()} · SAS ${call.sasCode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.testTag("accept_incoming_call_button")
            ) {
                Text("Join")
            }
        }
    }
}
