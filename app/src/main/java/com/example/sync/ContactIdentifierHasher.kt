package com.example.sync

import java.security.MessageDigest

data class DeviceContactCandidate(
    val displayName: String,
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList()
)

data class ContactSyncSummary(
    val scannedContactsCount: Int,
    val hashesCheckedCount: Int,
    val matchedFriendsCount: Int,
    val matchedFriendNames: List<String>
)

object ContactIdentifierHasher {
    private const val EMAIL_PREFIX = "ucn:v1:email:"
    private const val PHONE_PREFIX = "ucn:v1:phone:"

    fun normalizeEmail(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase().orEmpty()
        if (cleaned.length !in 3..320) return null
        val atIdx = cleaned.indexOf('@')
        if (atIdx <= 0 || atIdx >= cleaned.length - 2) return null
        val dotAfterAt = cleaned.indexOf('.', atIdx)
        if (dotAfterAt <= atIdx + 1 || dotAfterAt >= cleaned.length - 1) return null
        return cleaned
    }

    fun hashEmail(raw: String?): String? {
        val normalized = normalizeEmail(raw) ?: return null
        return sha256Hex(EMAIL_PREFIX + normalized)
    }

    fun normalizePhoneCandidates(raw: String?): List<String> {
        val digits = raw?.filter { it.isDigit() }.orEmpty()
        if (digits.length !in 7..15) return emptyList()
        val candidates = linkedSetOf(digits)
        if (digits.length > 10) {
            candidates.add(digits.takeLast(10))
        }
        return candidates.toList()
    }

    fun hashPhoneCandidates(raw: String?): List<String> {
        return normalizePhoneCandidates(raw).map { candidate ->
            sha256Hex(PHONE_PREFIX + candidate)
        }
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
