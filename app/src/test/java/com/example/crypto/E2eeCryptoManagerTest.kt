package com.example.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class E2eeCryptoManagerTest {

    @Test
    fun ecdhKeyWrapAndAesGcmRoundTrip_succeedsForDirectAndGroupMembers() {
        val alice = E2eeCryptoManager.generateIdentityKeyPair()
        val bob = E2eeCryptoManager.generateIdentityKeyPair()
        val sessionKey = E2eeCryptoManager.generateConversationKey()

        val wrappedForBob = E2eeCryptoManager.wrapConversationKeyForMember(
            conversationKey = sessionKey,
            senderPrivateKeyBase64 = alice.privateKeyBase64,
            recipientPublicKeyBase64 = bob.publicKeyBase64,
            senderPublicKeyBase64 = alice.publicKeyBase64
        )
        val bobUnwrapped = E2eeCryptoManager.unwrapConversationKey(
            wrappedPayload = wrappedForBob,
            recipientPrivateKeyBase64 = bob.privateKeyBase64
        )

        val encryptedText = E2eeCryptoManager.encryptText("Classified VoIP coordinates", sessionKey)
        assertNotEquals("Classified VoIP coordinates", encryptedText.ciphertextBase64)
        val decryptedText = E2eeCryptoManager.decryptText(
            encryptedText.ciphertextBase64,
            encryptedText.ivBase64,
            bobUnwrapped
        )
        assertEquals("Classified VoIP coordinates", decryptedText)

        val binaryMedia = ByteArray(1024) { (it % 251).toByte() }
        val encryptedMedia = E2eeCryptoManager.encryptBytes(binaryMedia, sessionKey)
        val decryptedMedia = E2eeCryptoManager.decryptBytes(
            encryptedMedia.ciphertextBase64,
            encryptedMedia.ivBase64,
            bobUnwrapped
        )
        assertArrayEquals(binaryMedia, decryptedMedia)
    }
}
