package com.example.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.crypto.IdentityKeyPairMaterial
import com.example.data.model.PublicIdentity
import com.example.data.model.UserProfile
import com.example.ui.theme.UcnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editDisplayNameAndRemovePhoto_enablesSaveAndInvokesCallback() {
        var savedName: String? = null
        var savedStatus: String? = null
        var clearedPhoto = false

        val profile = UserProfile(
            id = "u1",
            userId = "u1",
            email = "alice@ucn.test",
            displayName = "Alice",
            handle = "alice_ucn",
            photoUrl = "https://example.com/avatar.jpg"
        )
        val pub = PublicIdentity(
            id = "u1",
            userId = "u1",
            displayName = "Alice",
            handle = "alice_ucn",
            statusText = "Ready"
        )
        val keys = IdentityKeyPairMaterial(
            publicKeyBase64 = "pub",
            privateKeyBase64 = "priv",
            fingerprint = "AA11-BB22-CC33"
        )

        composeRule.setContent {
            UcnTheme(darkModePreference = "dark") {
                ProfileScreen(
                    userProfile = profile,
                    publicIdentity = pub,
                    identityKeys = keys,
                    isOnline = true,
                    isSaving = false,
                    onSaveProfile = { name, status, _, clear, _ ->
                        savedName = name
                        savedStatus = status
                        clearedPhoto = clear
                    }
                )
            }
        }

        // Initially unchanged -> Save disabled
        composeRule.onNodeWithTag("save_profile_button").performScrollTo().assertIsNotEnabled()

        // Clearing display name keeps Save disabled
        composeRule.onNodeWithTag("profile_display_name_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("save_profile_button").performScrollTo().assertIsNotEnabled()

        // Entering new display name & removing photo enables Save
        composeRule.onNodeWithTag("profile_display_name_input").performScrollTo().performTextInput("Alice Vance")
        composeRule.onNodeWithTag("remove_profile_photo_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("save_profile_button").performScrollTo().assertIsEnabled()

        composeRule.onNodeWithTag("save_profile_button").performScrollTo().performClick()

        assertEquals("Alice Vance", savedName)
        assertEquals("Ready", savedStatus)
        assertTrue(clearedPhoto)
    }
}
