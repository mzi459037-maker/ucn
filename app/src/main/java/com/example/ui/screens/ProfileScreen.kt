package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.model.PublicIdentity
import com.example.data.model.UserProfile
import com.example.ui.components.UserAvatar
import com.example.util.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userProfile: UserProfile?,
    publicIdentity: PublicIdentity?,
    identityKeys: IdentityKeyPairMaterial?,
    isOnline: Boolean,
    isSaving: Boolean,
    onSaveProfile: (displayName: String, statusText: String, newAvatarBase64: String?, clearPhoto: Boolean, phoneNumber: String?) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val savedName = userProfile?.displayName ?: "UCN User"
    val savedStatus = publicIdentity?.statusText ?: "E2EE Verified · UCN VoIP Ready"
    val savedPhone = userProfile?.phoneNumber.orEmpty()
    val savedAvatarB64 = userProfile?.avatarBase64
    val savedPhotoUrl = userProfile?.photoUrl

    var draftName by remember(savedName) { mutableStateOf(savedName) }
    var draftStatus by remember(savedStatus) { mutableStateOf(savedStatus) }
    var draftPhone by remember(savedPhone) { mutableStateOf(savedPhone) }
    var draftAvatarBase64 by remember(savedAvatarB64) { mutableStateOf<String?>(null) }
    var clearPhoto by remember(savedAvatarB64, savedPhotoUrl) { mutableStateOf(false) }
    var photoPickError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userProfile?.updatedAt, publicIdentity?.updatedAt) {
        if (!isSaving) {
            draftAvatarBase64 = null
            clearPhoto = false
        }
    }

    val effectiveAvatarB64 = when {
        clearPhoto -> null
        draftAvatarBase64 != null -> draftAvatarBase64
        else -> savedAvatarB64
    }
    val effectivePhotoUrl = if (clearPhoto || draftAvatarBase64 != null) null else savedPhotoUrl
    val hasAnyPhoto = !effectiveAvatarB64.isNullOrBlank() || !effectivePhotoUrl.isNullOrBlank()

    val trimmedName = draftName.trim()
    val nameError = if (trimmedName.isEmpty()) stringResource(R.string.profile_display_name_error) else null
    val hasUnsavedChanges =
        trimmedName != savedName.trim() ||
            draftStatus.trim() != savedStatus.trim() ||
            draftPhone.trim() != savedPhone.trim() ||
            draftAvatarBase64 != null ||
            (clearPhoto && (!savedAvatarB64.isNullOrBlank() || !savedPhotoUrl.isNullOrBlank()))

    // Zero-permission Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val encoded = MediaUtils.compressAvatarImage(context, uri)
            if (encoded != null) {
                draftAvatarBase64 = encoded
                clearPhoto = false
                photoPickError = null
            } else {
                photoPickError = "Unable to process selected image. Try another photo."
            }
        }
    }

    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.profile_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = if (isOnline) "Cloud Identity Sync Active" else "Offline Mode · Local Cache",
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Hero Avatar Editor Card
                Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                RoundedCornerShape(24.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawRect(
                                        brush = Brush.radialGradient(
                                            colors = listOf(primaryGlow, secondaryGlow, androidx.compose.ui.graphics.Color.Transparent),
                                            center = Offset(size.width * 0.5f, 0f),
                                            radius = size.width * 0.9f
                                        )
                                    )
                                }
                                .padding(horizontal = 20.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                UserAvatar(
                                    displayName = trimmedName.ifEmpty { savedName },
                                    photoUrl = effectivePhotoUrl,
                                    avatarBase64 = effectiveAvatarB64,
                                    size = 112.dp,
                                    showCryptoRing = true,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                        .testTag("profile_avatar_preview")
                                )

                                Surface(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .offset(x = 4.dp, y = 4.dp)
                                        .minimumInteractiveComponentSize()
                                        .size(40.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                        .testTag("profile_camera_badge")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.PhotoCamera,
                                            contentDescription = "Change profile picture",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = trimmedName.ifEmpty { savedName },
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "@${userProfile?.handle ?: "syncing"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("choose_profile_photo_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PhotoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.profile_choose_photo))
                                }

                                if (hasAnyPhoto) {
                                    OutlinedButton(
                                        onClick = {
                                            draftAvatarBase64 = null
                                            clearPhoto = true
                                            photoPickError = null
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("remove_profile_photo_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.profile_remove_photo))
                                    }
                                }
                            }

                            if (photoPickError != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = photoPickError.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                // Editable Profile Fields Card
                Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(20.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Public Profile Details",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            OutlinedTextField(
                                value = draftName,
                                onValueChange = { if (it.length <= 100) draftName = it },
                                label = { Text(stringResource(R.string.profile_display_name_label)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Person, contentDescription = null)
                                },
                                trailingIcon = {
                                    if (draftName.isNotEmpty()) {
                                        IconButton(onClick = { draftName = "" }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Clear display name")
                                        }
                                    }
                                },
                                isError = nameError != null,
                                supportingText = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = nameError ?: "Shown in 1:1 chats, groups, and VoIP calls"
                                        )
                                        Text("${draftName.length}/100")
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("profile_display_name_input")
                            )

                            OutlinedTextField(
                                value = draftStatus,
                                onValueChange = { if (it.length <= 140) draftStatus = it },
                                label = { Text(stringResource(R.string.profile_status_label)) },
                                placeholder = { Text("e.g. Available · E2EE Verified") },
                                supportingText = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Visible when peers verify your @handle")
                                        Text("${draftStatus.length}/140")
                                    }
                                },
                                maxLines = 2,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("profile_status_input")
                            )

                            OutlinedTextField(
                                value = draftPhone,
                                onValueChange = { if (it.length <= 32) draftPhone = it },
                                label = { Text(stringResource(R.string.profile_phone_label)) },
                                placeholder = { Text("e.g. +1 555 234 5678") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Phone, contentDescription = null)
                                },
                                trailingIcon = {
                                    if (draftPhone.isNotEmpty()) {
                                        IconButton(onClick = { draftPhone = "" }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Clear phone number")
                                        }
                                    }
                                },
                                supportingText = {
                                    Text("Stored privately & SHA-256 hashed so friends can discover you")
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("profile_phone_input")
                            )
                        }
                    }

                // Save / Discard Actions
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                onSaveProfile(
                                    trimmedName,
                                    draftStatus.trim(),
                                    draftAvatarBase64,
                                    clearPhoto,
                                    draftPhone.trim().ifBlank { null }
                                )
                            },
                            enabled = hasUnsavedChanges && nameError == null && !isSaving,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("save_profile_button")
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Saving & Syncing...")
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.profile_save_button))
                            }
                        }

                        AnimatedVisibility(visible = hasUnsavedChanges && !isSaving) {
                            TextButton(
                                onClick = {
                                    draftName = savedName
                                    draftStatus = savedStatus
                                    draftPhone = savedPhone
                                    draftAvatarBase64 = null
                                    clearPhoto = false
                                    photoPickError = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("discard_profile_button")
                            ) {
                                Text(stringResource(R.string.profile_discard_button))
                            }
                        }
                    }

                // Read-only Cryptographic Identity Metadata
                Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(20.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Verified UCN Credentials",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            IdentityInfoRow(
                                icon = Icons.Filled.AlternateEmail,
                                label = "UCN Handle",
                                value = "@${userProfile?.handle ?: "provisioning"}",
                                onCopy = if (userProfile != null) {
                                    { clipboard.setText(AnnotatedString(userProfile.handle)) }
                                } else null
                            )

                            IdentityInfoRow(
                                icon = Icons.Filled.Email,
                                label = "Signed-In Account",
                                value = userProfile?.email ?: "Verified Google Account"
                            )

                            IdentityInfoRow(
                                icon = Icons.Filled.Key,
                                label = "ECDH P-256 Key Fingerprint",
                                value = identityKeys?.fingerprint ?: "Generating...",
                                onCopy = if (identityKeys != null) {
                                    { clipboard.setText(AnnotatedString(identityKeys.fingerprint)) }
                                } else null
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun IdentityInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onCopy != null) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
