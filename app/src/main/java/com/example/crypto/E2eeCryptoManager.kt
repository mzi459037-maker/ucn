package com.example.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class IdentityKeyPairMaterial(
    val publicKeyBase64: String,
    val privateKeyBase64: String,
    val fingerprint: String
)

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String
)

object E2eeCryptoManager {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val AES_KEY_BITS = 256
    private val secureRandom = SecureRandom()

    fun generateIdentityKeyPair(): IdentityKeyPairMaterial {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val pair = kpg.generateKeyPair()
        val pubB64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        val privB64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        val fingerprint = computeFingerprint(pair.public.encoded)
        return IdentityKeyPairMaterial(
            publicKeyBase64 = pubB64,
            privateKeyBase64 = privB64,
            fingerprint = fingerprint
        )
    }

    fun decodePublicKey(publicKeyBase64: String): PublicKey {
        val bytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun decodePrivateKey(privateKeyBase64: String): PrivateKey {
        val bytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.take(12).joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString("-")
    }

    fun computeFingerprintFromBase64(publicKeyBase64: String): String {
        val bytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        return computeFingerprint(bytes)
    }

    fun generateConversationKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_BITS, secureRandom)
        return keyGen.generateKey()
    }

    fun wrapConversationKeyForMember(
        conversationKey: SecretKey,
        senderPrivateKeyBase64: String,
        recipientPublicKeyBase64: String,
        senderPublicKeyBase64: String
    ): String {
        val sharedAesKey = deriveSharedWrappingKey(senderPrivateKeyBase64, recipientPublicKeyBase64)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, sharedAesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val wrappedBytes = cipher.doFinal(conversationKey.encoded)
        // Format: "v1:<senderPubB64>:<ivB64>:<wrappedB64>"
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val wrappedB64 = Base64.encodeToString(wrappedBytes, Base64.NO_WRAP)
        return "v1:$senderPublicKeyBase64:$ivB64:$wrappedB64"
    }

    fun unwrapConversationKey(
        wrappedPayload: String,
        recipientPrivateKeyBase64: String
    ): SecretKey {
        val parts = wrappedPayload.split(":")
        require(parts.size == 4 && parts[0] == "v1") { "Unsupported wrapped key format" }
        val senderPubB64 = parts[1]
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val wrappedBytes = Base64.decode(parts[3], Base64.NO_WRAP)
        val sharedAesKey = deriveSharedWrappingKey(recipientPrivateKeyBase64, senderPubB64)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, sharedAesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val rawKey = cipher.doFinal(wrappedBytes)
        return SecretKeySpec(rawKey, "AES")
    }

    private fun deriveSharedWrappingKey(
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String
    ): SecretKey {
        val priv = decodePrivateKey(myPrivateKeyBase64)
        val pub = decodePublicKey(peerPublicKeyBase64)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val sharedSecret = ka.generateSecret()
        val derived = hkdfSha256(
            ikm = sharedSecret,
            salt = "UCN-E2EE-KEYWRAP-V1".toByteArray(Charsets.UTF_8),
            info = "AES-256-GCM".toByteArray(Charsets.UTF_8),
            length = 32
        )
        return SecretKeySpec(derived, "AES")
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        return okm
    }

    fun encryptText(plaintext: String, key: SecretKey): EncryptedPayload {
        return encryptBytes(plaintext.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptText(ciphertextBase64: String, ivBase64: String, key: SecretKey): String {
        val bytes = decryptBytes(ciphertextBase64, ivBase64, key)
        return String(bytes, Charsets.UTF_8)
    }

    fun encryptBytes(plainBytes: ByteArray, key: SecretKey): EncryptedPayload {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainBytes)
        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptBytes(ciphertextBase64: String, ivBase64: String, key: SecretKey): ByteArray {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    fun generateSasCode(conversationId: String, initiatorId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$conversationId:$initiatorId:${System.currentTimeMillis()}".toByteArray())
        val code1 = ((digest[0].toInt() and 0xFF) * 256 + (digest[1].toInt() and 0xFF)) % 900 + 100
        val code2 = ((digest[2].toInt() and 0xFF) * 256 + (digest[3].toInt() and 0xFF)) % 900 + 100
        return "$code1-$code2"
    }
}
