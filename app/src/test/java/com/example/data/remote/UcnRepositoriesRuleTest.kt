package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.example.crypto.E2eeCryptoManager
import com.example.data.model.PublicIdentity
import com.example.data.preferences.LocalAppPreferences
import com.example.sync.ContactIdentifierHasher
import com.example.sync.DeviceContactCandidate
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UcnRepositoriesRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun syncRepository_provisionsKeyringAndEnforcesOwnerIsolation() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID().toString().take(6)}@ucn.test")
        val syncRepo = UcnSyncRepository(firestore, auth)

        val keysResult = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = aliceUid,
                email = "alice@ucn.test",
                displayName = "Alice",
                photoUrl = null,
                deviceId = "dev_${UUID.randomUUID().toString().take(8)}",
                localPrefs = LocalAppPreferences()
            )
        }
        assertTrue(keysResult.isSuccess)

        val profile = withTimeout(TIMEOUT_MS) {
            syncRepo.observeUserProfile(aliceUid).first { it != null }
        }
        assertNotNull(profile)
        assertEquals(aliceUid, profile?.userId)

        // Update displayName and avatarBase64, then ensure re-provisioning preserves custom edits
        val updateResult = withTimeout(TIMEOUT_MS) {
            syncRepo.updateUserProfile(
                uid = aliceUid,
                displayName = "Alice Vance",
                statusText = "Available on UCN",
                avatarBase64 = "ZmFrZUF2YXRhckJ5dGVz",
                clearPhoto = false
            )
        }
        assertTrue("update failed: ${updateResult.exceptionOrNull()}", updateResult.isSuccess)

        val reProvision = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = aliceUid,
                email = "alice@ucn.test",
                displayName = "Alice",
                photoUrl = null,
                deviceId = "dev_${UUID.randomUUID().toString().take(8)}",
                localPrefs = LocalAppPreferences()
            )
        }
        assertTrue(reProvision.isSuccess)

        val updatedProfile = withTimeout(TIMEOUT_MS) {
            syncRepo.observeUserProfile(aliceUid).first { it?.displayName == "Alice Vance" }
        }
        assertEquals("Alice Vance", updatedProfile?.displayName)
        assertEquals("ZmFrZUF2YXRhckJ5dGVz", updatedProfile?.avatarBase64)

        // Sign in as Bob and verify Bob can lookup Alice's public identity (with updated name),
        // but CANNOT read Alice's private profile
        signInTestUser("bob_${UUID.randomUUID().toString().take(6)}@ucn.test")
        val publicResult = withTimeout(TIMEOUT_MS) {
            syncRepo.lookupPeerByHandleOrId(aliceUid)
        }
        assertTrue(publicResult.isSuccess)
        assertEquals(aliceUid, publicResult.getOrNull()?.userId)
        assertEquals("Alice Vance", publicResult.getOrNull()?.displayName)
        assertEquals("ZmFrZUF2YXRhckJ5dGVz", publicResult.getOrNull()?.avatarBase64)

        var permissionDenied = false
        try {
            withTimeout(TIMEOUT_MS) {
                syncRepo.observeUserProfile(aliceUid).first()
            }
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                permissionDenied = true
            }
        }
        assertTrue("Expected PERMISSION_DENIED reading another user's private profile", permissionDenied)
    }

    @Test
    fun chatAndCallRepositories_e2eeMessagesReceiptsAndVoipCallLifecycle() = runBlocking {
        val aliceUid = signInTestUser("alice_chat_${UUID.randomUUID().toString().take(6)}@ucn.test")
        val syncRepo = UcnSyncRepository(firestore, auth)
        val chatRepo = ChatRepository(firestore, auth)
        val callRepo = CallRepository(firestore, auth)

        val aliceKeys = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = aliceUid,
                email = "alice_chat@ucn.test",
                displayName = "Alice",
                photoUrl = null,
                deviceId = "dev_alice_1",
                localPrefs = LocalAppPreferences()
            ).getOrThrow()
        }

        val bobUid = signInTestUser("bob_chat_${UUID.randomUUID().toString().take(6)}@ucn.test")
        val bobKeys = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = bobUid,
                email = "bob_chat@ucn.test",
                displayName = "Bob",
                photoUrl = null,
                deviceId = "dev_bob_1",
                localPrefs = LocalAppPreferences()
            ).getOrThrow()
        }

        // Switch back to Alice to create group conversation & send E2EE message
        signInTestUser(auth.currentUser?.email ?: "")
        // Re-sign in Alice explicitly using fresh user lookup email? Better: use Alice's email stored above
        val aliceEmail = "alice_group_${UUID.randomUUID().toString().take(6)}@ucn.test"
        val aUid = signInTestUser(aliceEmail)
        val aKeys = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = aUid,
                email = aliceEmail,
                displayName = "Alice Group",
                photoUrl = null,
                deviceId = "dev_a_group",
                localPrefs = LocalAppPreferences()
            ).getOrThrow()
        }

        val bobPeer = PublicIdentity(
            userId = bobUid,
            displayName = "Bob",
            handle = "bob_peer",
            publicKeyBase64 = bobKeys.publicKeyBase64,
            keyFingerprint = bobKeys.fingerprint
        )

        val convId = withTimeout(TIMEOUT_MS) {
            chatRepo.createGroupConversation(
                currentUid = aUid,
                currentName = "Alice Group",
                myKeys = aKeys,
                title = "Alpha Squad",
                peers = listOf(bobPeer)
            ).getOrThrow()
        }

        val conv = withTimeout(TIMEOUT_MS) {
            chatRepo.observeConversation(convId).first { it != null }!!
        }

        val sessionKey = E2eeCryptoManager.unwrapConversationKey(
            wrappedPayload = conv.wrappedKeys.getValue(aUid),
            recipientPrivateKeyBase64 = aKeys.privateKeyBase64
        )

        val sendRes = withTimeout(TIMEOUT_MS) {
            chatRepo.sendEncryptedMessage(
                conversation = conv,
                senderId = aUid,
                senderName = "Alice Group",
                sessionKey = sessionKey,
                messageType = "text",
                plaintext = "Top secret rendezvous at 0900",
                isOnline = true
            )
        }
        assertTrue(sendRes.isSuccess)

        val messages = withTimeout(TIMEOUT_MS) {
            chatRepo.observeMessages(convId, aUid).first { it.isNotEmpty() }
        }
        val decrypted = E2eeCryptoManager.decryptText(messages.first().ciphertext, messages.first().iv, sessionKey)
        assertEquals("Top secret rendezvous at 0900", decrypted)

        // Start VoIP call & end it
        val callId = withTimeout(TIMEOUT_MS) {
            callRepo.startCall(
                conversation = conv,
                initiatorId = aUid,
                initiatorName = "Alice Group",
                callType = "video",
                codecProfile = "opus_48k_vp9_hd",
                sdpFingerprint = aKeys.fingerprint
            ).getOrThrow()
        }
        val call = withTimeout(TIMEOUT_MS) {
            callRepo.observeCall(callId).first { it != null }!!
        }
        assertEquals("ringing", call.status)

        val endRes = withTimeout(TIMEOUT_MS) { callRepo.endCall(call) }
        assertTrue(endRes.isSuccess)
    }

    @Test
    fun unauthenticatedCaller_rejectedWithPermissionDenied() = runBlocking {
        auth.signOut()
        val chatRepo = ChatRepository(firestore, auth)

        var denied = false
        try {
            withTimeout(TIMEOUT_MS) {
                chatRepo.observeConversations("someone").first()
            }
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                denied = true
            }
        }
        assertTrue("Expected PERMISSION_DENIED for unauthenticated caller", denied)
    }

    @Test
    fun contactSync_discoversUcnFriendsViaHashedIdentifiersAndEnforcesIsolation() = runBlocking {
        val syncRepo = UcnSyncRepository(firestore, auth)
        val suffix = UUID.randomUUID().toString().take(6)
        val aliceEmail = "alice_disc_$suffix@ucn.test"
        val alicePhone = "+1 (555) 867-$suffix".take(16)
        val bobEmail = "bob_disc_$suffix@ucn.test"

        // 1. Provision Alice and register her email + phone discovery hashes
        val aliceUid = signInTestUser(aliceEmail)
        val provAlice = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = aliceUid,
                email = aliceEmail,
                displayName = "Alice Discovery",
                photoUrl = null,
                deviceId = "dev_a_$suffix",
                localPrefs = LocalAppPreferences()
            )
        }
        assertTrue(provAlice.isSuccess)

        val phoneUpdate = withTimeout(TIMEOUT_MS) {
            syncRepo.updateUserProfile(
                uid = aliceUid,
                displayName = "Alice Discovery",
                statusText = "Available for VoIP",
                avatarBase64 = null,
                clearPhoto = false,
                phoneNumber = "5552345678",
                updatePhoneNumber = true
            )
        }
        assertTrue("Phone update failed: ${phoneUpdate.exceptionOrNull()}", phoneUpdate.isSuccess)

        // Verify SHA-256 hash doc exists in /contact_index without exposing plaintext email/phone
        val expectedEmailHash = ContactIdentifierHasher.hashEmail(aliceEmail)!!
        val emailHashDoc = withTimeout(TIMEOUT_MS) {
            firestore.collection("contact_index").document(expectedEmailHash).get().await()
        }
        assertTrue(emailHashDoc.exists())
        assertEquals(aliceUid, emailHashDoc.getString("userId"))
        assertFalse(emailHashDoc.data.orEmpty().containsKey("email"))
        assertFalse(emailHashDoc.data.orEmpty().containsKey("phoneNumber"))

        // 2. Provision Bob and sync simulated device contacts (matching Alice via email & local phone format)
        val bobUid = signInTestUser(bobEmail)
        val provBob = withTimeout(TIMEOUT_MS) {
            syncRepo.ensureUserProvisioned(
                uid = bobUid,
                email = bobEmail,
                displayName = "Bob Finder",
                photoUrl = null,
                deviceId = "dev_b_$suffix",
                localPrefs = LocalAppPreferences()
            )
        }
        assertTrue(provBob.isSuccess)

        val deviceContacts = listOf(
            DeviceContactCandidate(
                displayName = "Alice (Friend)",
                emails = listOf(aliceEmail.uppercase()),
                phones = listOf("555-234-5678")
            ),
            DeviceContactCandidate(
                displayName = "Non-UCN Contact",
                emails = listOf("not_registered_$suffix@other.example"),
                phones = listOf("555-000-9999")
            )
        )

        val syncRes = withTimeout(TIMEOUT_MS) {
            syncRepo.syncDeviceContactsWithFirestore(bobUid, deviceContacts)
        }
        assertTrue("Contact sync failed: ${syncRes.exceptionOrNull()}", syncRes.isSuccess)
        val summary = syncRes.getOrThrow()
        assertEquals(2, summary.scannedContactsCount)
        assertEquals(1, summary.matchedFriendsCount)
        assertTrue(summary.matchedFriendNames.contains("Alice Discovery"))

        // Verify Alice appears in Bob's private Firestore contacts with discovery metadata
        val bobContacts = withTimeout(TIMEOUT_MS) {
            syncRepo.observeContacts(bobUid).first { list -> list.any { it.peerUserId == aliceUid } }
        }
        val aliceContact = bobContacts.first { it.peerUserId == aliceUid }
        assertEquals("device_contacts", aliceContact.discoverySource)
        assertEquals("Alice (Friend)", aliceContact.deviceContactName)

        // 3. Negative checks: Bob cannot scrape Alice's private contacts or index collection
        var deniedContactsRead = false
        try {
            withTimeout(TIMEOUT_MS) { syncRepo.observeContacts(aliceUid).first() }
        } catch (e: Exception) {
            if (e.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                deniedContactsRead = true
            }
        }
        assertTrue("Expected PERMISSION_DENIED reading another user's contacts", deniedContactsRead)

        // 4. Negative check: Unauthenticated caller cannot sync contacts
        auth.signOut()
        val unauthSync = syncRepo.syncDeviceContactsWithFirestore(bobUid, deviceContacts)
        assertTrue(unauthSync.isFailure)
    }

    private companion object {
        const val TIMEOUT_MS = 8000L
    }
}
