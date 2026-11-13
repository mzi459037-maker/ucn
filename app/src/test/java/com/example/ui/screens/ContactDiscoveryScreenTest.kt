package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.ContactEntry
import com.example.data.model.PublicIdentity
import com.example.sync.ContactSyncRuntimeState
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
class ContactDiscoveryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withoutPermission_showsDisclosurePromptDialog() {
        composeRule.setContent {
            UcnTheme(darkModePreference = "dark") {
                ContactDiscoveryScreen(
                    contacts = emptyList(),
                    hasContactsPermission = false,
                    autoSyncEnabled = false,
                    syncStatus = ContactSyncRuntimeState(),
                    lastSavedSyncMs = 0L,
                    lastScannedContactsCount = 0,
                    isOnline = true,
                    onBack = {},
                    onPermissionResult = {},
                    onToggleAutoSync = {},
                    onSyncNow = {},
                    onStartChat = {},
                    onStartVoiceCall = {},
                    onStartVideoCall = {},
                    onOpenProfile = {}
                )
            }
        }

        composeRule.onNodeWithTag("grant_contacts_permission_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("confirm_contact_disclosure_button").assertIsDisplayed()
    }

    @Test
    fun withPermissionAndDiscoveredFriend_triggersSyncAndStartChat() {
        var syncTriggered = false
        var chattedPeer: PublicIdentity? = null
        val friend = ContactEntry(
            id = "peer_1",
            userId = "me_1",
            peerUserId = "peer_1",
            peerDisplayName = "Sarah Connor",
            peerHandle = "sarah_ucn",
            peerPublicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234",
            peerKeyFingerprint = "AA11-BB22-CC33-DD44",
            verified = true,
            deviceContactName = "Sarah Mobile",
            discoverySource = "device_contacts",
            matchedVia = "phone"
        )

        composeRule.setContent {
            UcnTheme(darkModePreference = "dark") {
                ContactDiscoveryScreen(
                    contacts = listOf(friend),
                    hasContactsPermission = true,
                    autoSyncEnabled = true,
                    syncStatus = ContactSyncRuntimeState(lastScannedContactsCount = 12, lastMatchedFriendsCount = 1),
                    lastSavedSyncMs = 1700000000000L,
                    lastScannedContactsCount = 12,
                    isOnline = true,
                    onBack = {},
                    onPermissionResult = {},
                    onToggleAutoSync = {},
                    onSyncNow = { syncTriggered = true },
                    onStartChat = { chattedPeer = it },
                    onStartVoiceCall = {},
                    onStartVideoCall = {},
                    onOpenProfile = {}
                )
            }
        }

        composeRule.onNodeWithTag("sync_contacts_now_button").performScrollTo().performClick()
        assertTrue(syncTriggered)

        composeRule.onNodeWithTag("contact_discovery_list")
            .performScrollToNode(hasTestTag("discovered_friend_card_peer_1"))
        composeRule.onNodeWithText("Sarah Connor").assertIsDisplayed()
        composeRule.onNodeWithText("Contact: Sarah Mobile · via phone").assertIsDisplayed()
        composeRule.onNodeWithTag("friend_chat_button_peer_1").performScrollTo().performClick()
        assertEquals("peer_1", chattedPeer?.userId)
    }
}
