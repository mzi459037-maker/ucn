package com.example.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIdentifierHasherTest {

    @Test
    fun normalizeAndHashEmail_isCaseInsensitiveAndDeterministic() {
        val h1 = ContactIdentifierHasher.hashEmail("  Alice.Vance@Example.COM ")
        val h2 = ContactIdentifierHasher.hashEmail("alice.vance@example.com")
        assertEquals(h1, h2)
        assertEquals(64, h1?.length)
        assertTrue(h1!!.matches(Regex("^[a-f0-9]{64}$")))
        assertNull(ContactIdentifierHasher.hashEmail("not-an-email"))
    }

    @Test
    fun normalizePhoneCandidates_matchesInternationalAndDomesticLast10() {
        val intlHashes = ContactIdentifierHasher.hashPhoneCandidates("+1 (555) 234-5678")
        val localHashes = ContactIdentifierHasher.hashPhoneCandidates("555.234.5678")
        assertTrue(intlHashes.any { it in localHashes })
        assertNotEquals(
            ContactIdentifierHasher.hashPhoneCandidates("5552345678"),
            ContactIdentifierHasher.hashPhoneCandidates("5552345679")
        )
        assertTrue(ContactIdentifierHasher.normalizePhoneCandidates("123").isEmpty())
    }
}
