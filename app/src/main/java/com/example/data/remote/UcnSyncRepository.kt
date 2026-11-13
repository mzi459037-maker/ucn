package com.example.data.remote

import android.os.Build
import com.example.crypto.E2eeCryptoManager
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.model.ContactEntry
import com.example.data.model.HandleDirectory
import com.example.data.model.PublicIdentity
import com.example.data.model.SyncedDevice
import com.example.data.model.UserKeyringBackup
import com.example.data.model.UserProfile
import com.example.data.preferences.LocalAppPreferences
import com.example.sync.ContactIdentifierHasher
import com.example.sync.ContactSyncSummary
import com.example.sync.DeviceContactCandidate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class UcnSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in with Google.")

    fun normalizeHandle(raw: String): String {
        val cleaned = raw.trim().lowercase().replace(Regex("[^a-z0-9_\\-]"), "_")
        return if (cleaned.length >= 3) cleaned.take(40) else "ucn_${cleaned}".padEnd(6, '0')
    }

    suspend fun ensureUserProvisioned(
        uid: String,
        email: String,
        displayName: String,
        photoUrl: String?,
        deviceId: String,
        localPrefs: LocalAppPreferences
    ): Result<IdentityKeyPairMaterial> {
        return try {
            val keyringRef = db.collection("users").document(uid)
                .collection("private").document("keyring")
            val existingKeyringSnap = keyringRef.get().await()
            val keyMaterial = if (existingKeyringSnap.exists()) {
                val backup = existingKeyringSnap.toObject(
                    UserKeyringBackup::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (backup != null && backup.publicKeyBase64.isNotBlank() && backup.privateKeyBase64.isNotBlank()) {
                    IdentityKeyPairMaterial(
                        publicKeyBase64 = backup.publicKeyBase64,
                        privateKeyBase64 = backup.privateKeyBase64,
                        fingerprint = backup.keyFingerprint
                    )
                } else {
                    createAndPersistKeyring(uid, keyringRef.path)
                }
            } else {
                createAndPersistKeyring(uid, keyringRef.path)
            }

            val defaultName = displayName.ifBlank { email.substringBefore("@").ifBlank { "UCN User" } }.take(100)
            val defaultHandle = normalizeHandle(
                email.substringBefore("@").ifBlank { "user_${uid.take(6)}" } + "_" + uid.take(4).lowercase()
            )

            // Profile (preserve user-edited displayName and profile photo if already saved)
            val userRef = db.collection("users").document(uid)
            val userSnap = userRef.get().await()
            val resolvedHandle = if (userSnap.exists()) {
                userSnap.getString("handle")?.takeIf { it.isNotBlank() } ?: defaultHandle
            } else {
                defaultHandle
            }
            val resolvedName = if (userSnap.exists()) {
                userSnap.getString("displayName")?.takeIf { it.isNotBlank() } ?: defaultName
            } else {
                defaultName
            }
            val existingPhotoUrl = if (userSnap.exists()) userSnap.getString("photoUrl") else null
            val existingAvatarB64 = if (userSnap.exists()) userSnap.getString("avatarBase64") else null

            if (!userSnap.exists()) {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "email" to email.ifBlank { "$uid@ucn.local" },
                    "displayName" to resolvedName,
                    "handle" to resolvedHandle,
                    "darkMode" to localPrefs.darkMode,
                    "readReceiptsEnabled" to localPrefs.readReceiptsEnabled,
                    "voipCodecProfile" to localPrefs.voipCodecProfile,
                    "autoSyncEnabled" to localPrefs.autoSyncEnabled,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!photoUrl.isNullOrBlank()) {
                    payload["photoUrl"] = photoUrl.take(1000)
                }
                userRef.set(payload).await()
            } else {
                val updates = mutableMapOf<String, Any>(
                    "displayName" to resolvedName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (existingPhotoUrl.isNullOrBlank() && existingAvatarB64.isNullOrBlank() && !photoUrl.isNullOrBlank()) {
                    updates["photoUrl"] = photoUrl.take(1000)
                }
                userRef.update(updates).await()
            }

            // Public Identity (no PII)
            val publicRef = db.collection("users_public").document(uid)
            val publicSnap = publicRef.get().await()
            if (!publicSnap.exists()) {
                val publicPayload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to resolvedName,
                    "handle" to resolvedHandle,
                    "publicKeyBase64" to keyMaterial.publicKeyBase64,
                    "keyFingerprint" to keyMaterial.fingerprint,
                    "statusText" to "E2EE Verified · UCN VoIP Ready",
                    "isOnline" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val initialPhoto = existingPhotoUrl ?: photoUrl
                if (!initialPhoto.isNullOrBlank()) {
                    publicPayload["photoUrl"] = initialPhoto.take(1000)
                }
                if (!existingAvatarB64.isNullOrBlank()) {
                    publicPayload["avatarBase64"] = existingAvatarB64
                }
                publicRef.set(publicPayload).await()
            } else {
                val publicUpdates = mutableMapOf<String, Any>(
                    "displayName" to resolvedName,
                    "handle" to resolvedHandle,
                    "publicKeyBase64" to keyMaterial.publicKeyBase64,
                    "keyFingerprint" to keyMaterial.fingerprint,
                    "isOnline" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!existingAvatarB64.isNullOrBlank()) {
                    publicUpdates["avatarBase64"] = existingAvatarB64
                }
                publicRef.update(publicUpdates).await()
            }

            // Handle Directory
            val handleRef = db.collection("handles").document(resolvedHandle)
            val handleSnap = handleRef.get().await()
            if (!handleSnap.exists()) {
                handleRef.set(
                    mapOf(
                        "userId" to uid,
                        "handle" to resolvedHandle,
                        "displayName" to resolvedName,
                        "publicKeyBase64" to keyMaterial.publicKeyBase64,
                        "keyFingerprint" to keyMaterial.fingerprint,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else if (handleSnap.getString("userId") == uid) {
                handleRef.update(
                    mapOf(
                        "displayName" to resolvedName,
                        "publicKeyBase64" to keyMaterial.publicKeyBase64,
                        "keyFingerprint" to keyMaterial.fingerprint,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            // Synced Device registration
            val deviceRef = db.collection("users").document(uid).collection("devices").document(deviceId)
            val deviceSnap = deviceRef.get().await()
            val deviceName = "${Build.MANUFACTURER.orEmpty().trim()} ${Build.MODEL.orEmpty().trim()}".trim()
                .ifBlank { "Android Device" }.take(120)
            if (!deviceSnap.exists()) {
                deviceRef.set(
                    mapOf(
                        "userId" to uid,
                        "deviceName" to deviceName,
                        "platform" to "Android ${Build.VERSION.RELEASE ?: "15"}",
                        "appVersion" to "1.0.0",
                        "keyFingerprint" to keyMaterial.fingerprint,
                        "lastSyncAt" to FieldValue.serverTimestamp(),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                deviceRef.update(
                    mapOf(
                        "deviceName" to deviceName,
                        "platform" to "Android ${Build.VERSION.RELEASE ?: "15"}",
                        "keyFingerprint" to keyMaterial.fingerprint,
                        "lastSyncAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val existingPhone = if (userSnap.exists()) userSnap.getString("phoneNumber") else null
            val resolvedEmail = (if (userSnap.exists()) userSnap.getString("email") else null)
                ?.takeIf { it.isNotBlank() } ?: email.ifBlank { "$uid@ucn.local" }
            registerUserDiscoveryHashes(
                uid = uid,
                email = resolvedEmail,
                phoneNumber = existingPhone
            )

            Result.success(keyMaterial)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid")
            Result.failure(e)
        }
    }

    private suspend fun createAndPersistKeyring(uid: String, path: String): IdentityKeyPairMaterial {
        val generated = E2eeCryptoManager.generateIdentityKeyPair()
        val payload = mapOf(
            "userId" to uid,
            "publicKeyBase64" to generated.publicKeyBase64,
            "privateKeyBase64" to generated.privateKeyBase64,
            "keyFingerprint" to generated.fingerprint,
            "keyVersion" to 1L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.document(path).set(payload).await()
        return generated
    }

    suspend fun rotateIdentityKeys(uid: String, deviceId: String): Result<IdentityKeyPairMaterial> {
        return try {
            requireUserId()
            val generated = E2eeCryptoManager.generateIdentityKeyPair()
            val keyringRef = db.collection("users").document(uid).collection("private").document("keyring")
            val currentSnap = keyringRef.get().await()
            val nextVersion = (currentSnap.getLong("keyVersion") ?: 1L) + 1L
            if (currentSnap.exists()) {
                keyringRef.update(
                    mapOf(
                        "publicKeyBase64" to generated.publicKeyBase64,
                        "privateKeyBase64" to generated.privateKeyBase64,
                        "keyFingerprint" to generated.fingerprint,
                        "keyVersion" to nextVersion,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                keyringRef.set(
                    mapOf(
                        "userId" to uid,
                        "publicKeyBase64" to generated.publicKeyBase64,
                        "privateKeyBase64" to generated.privateKeyBase64,
                        "keyFingerprint" to generated.fingerprint,
                        "keyVersion" to 1L,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val publicRef = db.collection("users_public").document(uid)
            val publicSnap = publicRef.get().await()
            val handle = publicSnap.getString("handle")
            if (publicSnap.exists()) {
                publicRef.update(
                    mapOf(
                        "publicKeyBase64" to generated.publicKeyBase64,
                        "keyFingerprint" to generated.fingerprint,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            if (!handle.isNullOrBlank()) {
                val handleRef = db.collection("handles").document(handle)
                if (handleRef.get().await().exists()) {
                    handleRef.update(
                        mapOf(
                            "publicKeyBase64" to generated.publicKeyBase64,
                            "keyFingerprint" to generated.fingerprint,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            val deviceRef = db.collection("users").document(uid).collection("devices").document(deviceId)
            if (deviceRef.get().await().exists()) {
                deviceRef.update(
                    mapOf(
                        "keyFingerprint" to generated.fingerprint,
                        "lastSyncAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(generated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid/private/keyring")
            Result.failure(e)
        }
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> =
        db.collection("users").document(uid)
            .snapshots()
            .map { it.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, "users/$uid")
                }
                throw error
            }

    fun observePublicIdentity(uid: String): Flow<PublicIdentity?> =
        db.collection("users_public").document(uid)
            .snapshots()
            .map { it.toObject(PublicIdentity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, "users_public/$uid")
                }
                throw error
            }

    fun observeSyncedDevices(uid: String): Flow<List<SyncedDevice>> =
        db.collection("users").document(uid).collection("devices")
            .snapshots()
            .map { snap ->
                snap.toObjects(SyncedDevice::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.lastSyncAt?.toDate()?.time ?: 0L }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, "users/$uid/devices")
                }
                throw error
            }

    fun observeContacts(uid: String): Flow<List<ContactEntry>> =
        db.collection("users").document(uid).collection("contacts")
            .snapshots()
            .map { snap ->
                snap.toObjects(ContactEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedBy { it.peerDisplayName.lowercase() }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == uid) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, "users/$uid/contacts")
                }
                throw error
            }

    suspend fun lookupPeerByHandleOrId(query: String): Result<PublicIdentity?> {
        val trimmed = query.trim().removePrefix("@")
        if (trimmed.isBlank()) return Result.success(null)
        return try {
            requireUserId()
            // First try exact handle lookup
            val normalizedHandle = trimmed.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_")
            if (normalizedHandle.length >= 3) {
                val handleSnap = db.collection("handles").document(normalizedHandle).get().await()
                if (handleSnap.exists()) {
                    val dir = handleSnap.toObject(HandleDirectory::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    if (dir != null) {
                        val pubSnap = db.collection("users_public").document(dir.userId).get().await()
                        val pub = pubSnap.toObject(PublicIdentity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        if (pub != null) return Result.success(pub)
                        return Result.success(
                            PublicIdentity(
                                id = dir.userId,
                                userId = dir.userId,
                                displayName = dir.displayName,
                                handle = dir.handle,
                                publicKeyBase64 = dir.publicKeyBase64,
                                keyFingerprint = dir.keyFingerprint,
                                isOnline = true
                            )
                        )
                    }
                }
            }
            // Fallback: exact UID lookup on users_public
            if (trimmed.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
                val pubSnap = db.collection("users_public").document(trimmed).get().await()
                if (pubSnap.exists()) {
                    return Result.success(
                        pubSnap.toObject(PublicIdentity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    )
                }
            }
            Result.success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "handles/$trimmed")
            Result.failure(e)
        }
    }

    suspend fun saveContact(
        uid: String,
        peer: PublicIdentity,
        verified: Boolean = true,
        deviceContactName: String? = null,
        discoverySource: String = "manual",
        matchedVia: String? = null
    ): Result<Unit> {
        return try {
            requireUserId()
            val contactRef = db.collection("users").document(uid).collection("contacts").document(peer.userId)
            val snap = contactRef.get().await()
            val cleanDeviceName = deviceContactName?.trim()?.take(120)?.ifBlank { null }
            val cleanPhotoUrl = peer.photoUrl?.take(1000)?.ifBlank { null }
            val cleanAvatarB64 = peer.avatarBase64?.takeIf { it.length <= 120_000 && it.isNotBlank() }
            if (!snap.exists()) {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "peerUserId" to peer.userId,
                    "peerDisplayName" to peer.displayName.take(100),
                    "peerHandle" to peer.handle.take(40),
                    "peerPublicKeyBase64" to peer.publicKeyBase64,
                    "peerKeyFingerprint" to peer.keyFingerprint,
                    "verified" to verified,
                    "discoverySource" to discoverySource,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanDeviceName != null) payload["deviceContactName"] = cleanDeviceName
                if (!matchedVia.isNullOrBlank()) payload["matchedVia"] = matchedVia
                if (cleanPhotoUrl != null) payload["peerPhotoUrl"] = cleanPhotoUrl
                if (cleanAvatarB64 != null) payload["peerAvatarBase64"] = cleanAvatarB64
                contactRef.set(payload).await()
            } else {
                val updates = mutableMapOf<String, Any?>(
                    "peerDisplayName" to peer.displayName.take(100),
                    "peerHandle" to peer.handle.take(40),
                    "peerPublicKeyBase64" to peer.publicKeyBase64,
                    "peerKeyFingerprint" to peer.keyFingerprint,
                    "verified" to verified,
                    "discoverySource" to discoverySource,
                    "peerPhotoUrl" to cleanPhotoUrl,
                    "peerAvatarBase64" to cleanAvatarB64,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanDeviceName != null) updates["deviceContactName"] = cleanDeviceName
                if (!matchedVia.isNullOrBlank()) updates["matchedVia"] = matchedVia
                contactRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid/contacts/${peer.userId}")
            Result.failure(e)
        }
    }

    suspend fun registerUserDiscoveryHashes(
        uid: String,
        email: String?,
        phoneNumber: String?
    ): Result<Int> {
        return try {
            val authUid = requireUserId()
            require(authUid == uid) { "Hash registration restricted to authenticated owner" }

            val desired = linkedMapOf<String, String>()
            ContactIdentifierHasher.hashEmail(email)?.let { hash ->
                desired[hash] = "email"
            }
            ContactIdentifierHasher.hashPhoneCandidates(phoneNumber).forEach { hash ->
                desired[hash] = "phone"
            }

            // Clean up any stale hashes (e.g., if user changed or cleared their phone number)
            val existingDocs = db.collection("contact_index")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            existingDocs.documents.forEach { doc ->
                if (doc.id !in desired) {
                    doc.reference.delete().await()
                }
            }

            var indexedCount = 0
            desired.forEach { (hashId, type) ->
                val ref = db.collection("contact_index").document(hashId)
                val snap = ref.get().await()
                if (!snap.exists()) {
                    ref.set(
                        mapOf(
                            "hashId" to hashId,
                            "userId" to uid,
                            "identifierType" to type,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    indexedCount++
                } else if (snap.getString("userId") == uid) {
                    ref.update("updatedAt", FieldValue.serverTimestamp()).await()
                    indexedCount++
                }
            }
            Result.success(indexedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "contact_index/$uid")
            Result.failure(e)
        }
    }

    suspend fun syncDeviceContactsWithFirestore(
        uid: String,
        deviceContacts: List<DeviceContactCandidate>
    ): Result<ContactSyncSummary> {
        return try {
            val authUid = requireUserId()
            require(authUid == uid) { "Contact sync restricted to authenticated owner" }

            data class HashQueryMeta(val hashId: String, val contactName: String, val matchedVia: String)

            val hashLookupMap = linkedMapOf<String, HashQueryMeta>()
            deviceContacts.forEach { contact ->
                val label = contact.displayName.trim().take(120).ifBlank { "Contact" }
                contact.emails.forEach { email ->
                    val hash = ContactIdentifierHasher.hashEmail(email)
                    if (hash != null && hash !in hashLookupMap) {
                        hashLookupMap[hash] = HashQueryMeta(hash, label, "email")
                    }
                }
                contact.phones.forEach { phone ->
                    ContactIdentifierHasher.hashPhoneCandidates(phone).forEach { hash ->
                        if (hash !in hashLookupMap) {
                            hashLookupMap[hash] = HashQueryMeta(hash, label, "phone")
                        }
                    }
                }
            }

            if (hashLookupMap.isEmpty()) {
                return Result.success(
                    ContactSyncSummary(
                        scannedContactsCount = deviceContacts.size,
                        hashesCheckedCount = 0,
                        matchedFriendsCount = 0,
                        matchedFriendNames = emptyList()
                    )
                )
            }

            data class DiscoveredPeerRef(val peerUid: String, val deviceContactName: String, val matchedVia: String)
            val discoveredByUid = linkedMapOf<String, DiscoveredPeerRef>()

            val allHashes = hashLookupMap.values.toList()
            allHashes.chunked(12).forEach { chunk ->
                val results = coroutineScope {
                    chunk.map { meta ->
                        async {
                            try {
                                val snap = db.collection("contact_index").document(meta.hashId).get().await()
                                if (snap.exists()) {
                                    val peerUid = snap.getString("userId")
                                    if (!peerUid.isNullOrBlank() && peerUid != uid) {
                                        DiscoveredPeerRef(
                                            peerUid = peerUid,
                                            deviceContactName = meta.contactName,
                                            matchedVia = snap.getString("identifierType") ?: meta.matchedVia
                                        )
                                    } else null
                                } else null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }
                results.filterNotNull().forEach { match ->
                    discoveredByUid.putIfAbsent(match.peerUid, match)
                }
            }

            val savedNames = mutableListOf<String>()
            discoveredByUid.values.chunked(10).forEach { peerChunk ->
                val identities = coroutineScope {
                    peerChunk.map { match ->
                        async {
                            try {
                                val pubSnap = db.collection("users_public").document(match.peerUid).get().await()
                                val peer = pubSnap.toObject(
                                    PublicIdentity::class.java,
                                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                                )
                                if (peer != null && peer.userId.isNotBlank()) match to peer else null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }
                identities.filterNotNull().forEach { (match, peer) ->
                    val saveRes = saveContact(
                        uid = uid,
                        peer = peer,
                        verified = true,
                        deviceContactName = match.deviceContactName,
                        discoverySource = "device_contacts",
                        matchedVia = match.matchedVia
                    )
                    if (saveRes.isSuccess) {
                        savedNames.add(peer.displayName)
                    }
                }
            }

            Result.success(
                ContactSyncSummary(
                    scannedContactsCount = deviceContacts.size,
                    hashesCheckedCount = hashLookupMap.size,
                    matchedFriendsCount = savedNames.size,
                    matchedFriendNames = savedNames
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "contact_index")
            Result.failure(e)
        }
    }

    suspend fun updateUserProfile(
        uid: String,
        displayName: String,
        statusText: String?,
        avatarBase64: String?,
        clearPhoto: Boolean,
        phoneNumber: String? = null,
        updatePhoneNumber: Boolean = false,
        awaitServerAck: Boolean = true
    ): Result<Unit> {
        val cleanName = displayName.trim().take(100)
        require(cleanName.isNotEmpty()) { "Display name cannot be blank" }
        val cleanStatus = statusText?.trim()?.take(140)?.ifBlank { null }
        val cleanPhone = phoneNumber?.trim()?.take(32)?.ifBlank { null }

        return try {
            val authUid = requireUserId()
            require(authUid == uid) { "Profile updates are restricted to the authenticated owner" }

            val userUpdates = mutableMapOf<String, Any?>(
                "displayName" to cleanName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (updatePhoneNumber || phoneNumber != null) {
                userUpdates["phoneNumber"] = cleanPhone
            }
            if (clearPhoto) {
                userUpdates["photoUrl"] = null
                userUpdates["avatarBase64"] = null
            } else if (avatarBase64 != null) {
                require(avatarBase64.length <= 120_000) { "Profile image exceeds maximum size" }
                userUpdates["avatarBase64"] = avatarBase64
            }

            val publicUpdates = mutableMapOf<String, Any?>(
                "displayName" to cleanName,
                "statusText" to cleanStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (clearPhoto) {
                publicUpdates["photoUrl"] = null
                publicUpdates["avatarBase64"] = null
            } else if (avatarBase64 != null) {
                publicUpdates["avatarBase64"] = avatarBase64
            }

            val userTask = db.collection("users").document(uid).update(userUpdates)
            val publicTask = db.collection("users_public").document(uid).update(publicUpdates)

            if (awaitServerAck) {
                userTask.await()
                publicTask.await()
                val userSnap = db.collection("users").document(uid).get().await()
                val handle = userSnap.getString("handle")
                if (!handle.isNullOrBlank()) {
                    val handleRef = db.collection("handles").document(handle)
                    if (handleRef.get().await().exists()) {
                        handleRef.update(
                            mapOf(
                                "displayName" to cleanName,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }
                if (updatePhoneNumber || phoneNumber != null) {
                    registerUserDiscoveryHashes(
                        uid = uid,
                        email = userSnap.getString("email"),
                        phoneNumber = cleanPhone
                    )
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateSyncedPreferences(
        uid: String,
        darkMode: String? = null,
        readReceiptsEnabled: Boolean? = null,
        voipCodecProfile: String? = null,
        autoSyncEnabled: Boolean? = null
    ): Result<Unit> {
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>("updatedAt" to FieldValue.serverTimestamp())
            if (darkMode != null) updates["darkMode"] = darkMode
            if (readReceiptsEnabled != null) updates["readReceiptsEnabled"] = readReceiptsEnabled
            if (voipCodecProfile != null) updates["voipCodecProfile"] = voipCodecProfile
            if (autoSyncEnabled != null) updates["autoSyncEnabled"] = autoSyncEnabled
            db.collection("users").document(uid).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun touchDeviceSync(uid: String, deviceId: String): Result<Unit> {
        return try {
            requireUserId()
            db.collection("users").document(uid).collection("devices").document(deviceId)
                .update("lastSyncAt", FieldValue.serverTimestamp()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid/devices/$deviceId")
            Result.failure(e)
        }
    }

    suspend fun removeDevice(uid: String, deviceId: String): Result<Unit> {
        return try {
            requireUserId()
            db.collection("users").document(uid).collection("devices").document(deviceId)
                .delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid/devices/$deviceId")
            Result.failure(e)
        }
    }
}
