package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.data.model.DecryptedConversationSummary
import com.example.ui.PeerLookupState
import com.example.ui.theme.UcnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatsListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleConversations(): List<DecryptedConversationSummary> = listOf(
        DecryptedConversationSummary(
            id = "conv_alice",
            type = "direct",
            title = "Alice Vance",
            memberUids = listOf("me", "alice"),
            memberNames = mapOf("me" to "Me", "alice" to "Alice Vance"),
            lastPreviewText = "Encrypted call ended",
            lastMessageSenderName = "Alice Vance",
            lastMessageType = "text",
            lastActivityMs = 1700000003000L,
            activeCallId = null
        ),
        DecryptedConversationSummary(
            id = "conv_bob",
            type = "direct",
            title = "Bob Mercer",
            memberUids = listOf("me", "bob"),
            memberNames = mapOf("me" to "Me", "bob" to "Bob Mercer"),
            lastPreviewText = "Did Alice send the key?",
            lastMessageSenderName = "Bob Mercer",
            lastMessageType = "text",
            lastActivityMs = 1700000002000L,
            activeCallId = null
        ),
        DecryptedConversationSummary(
            id = "conv_ops",
            type = "group",
            title = "Ops Response Team",
            memberUids = listOf("me", "alice", "bob"),
            memberNames = mapOf("me" to "Me", "alice" to "Alice Vance", "bob" to "Bob Mercer"),
            lastPreviewText = "Rotation complete",
            lastMessageSenderName = "Me",
            lastMessageType = "text",
            lastActivityMs = 1700000001000L,
            activeCallId = null
        )
    )

    @Test
    fun searchBar_filtersConversationsStrictlyByNameAndClearsResults() {
        composeRule.setContent {
            UcnTheme(darkModePreference = "dark") {
                ChatsListScreen(
                    conversations = sampleConversations(),
                    contacts = emptyList(),
                    userProfile = null,
                    identityKeys = null,
                    isOnline = true,
                    transportLabel = "4G/5G",
                    pendingOutboxCount = 0,
                    peerLookupState = PeerLookupState.Idle,
                    onOpenConversation = {},
                    onLookupPeer = {},
                    onClearLookup = {},
                    onStartDirectChat = { _, _ -> },
                    onCreateGroupChat = { _, _, _ -> },
                    onSyncNow = {},
                    onOpenProfile = {}
                )
            }
        }

        // All three visible initially
        composeRule.onNodeWithTag("conversation_search_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_card_conv_alice").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_card_conv_bob").assertIsDisplayed()

        // Filter by conversation name "alice" (case-insensitive); Bob has "Alice" only in preview text so should NOT match
        composeRule.onNodeWithTag("chats_search_input").performTextInput("alice")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chats_search_count_badge").assertTextEquals("1")
        composeRule.onNodeWithTag("conversation_card_conv_alice").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_card_conv_bob").assertDoesNotExist()
        composeRule.onNodeWithTag("conversation_card_conv_ops").assertDoesNotExist()

        // Clear search restores all conversations
        composeRule.onNodeWithTag("chats_search_clear_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("conversation_card_conv_alice").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_card_conv_bob").assertIsDisplayed()
    }

    @Test
    fun searchBar_unmatchedQueryShowsEmptySearchStateWithClearAction() {
        composeRule.setContent {
            UcnTheme(darkModePreference = "dark") {
                ChatsListScreen(
                    conversations = sampleConversations(),
                    contacts = emptyList(),
                    userProfile = null,
                    identityKeys = null,
                    isOnline = true,
                    transportLabel = "Wi-Fi",
                    pendingOutboxCount = 0,
                    peerLookupState = PeerLookupState.Idle,
                    onOpenConversation = {},
                    onLookupPeer = {},
                    onClearLookup = {},
                    onStartDirectChat = { _, _ -> },
                    onCreateGroupChat = { _, _, _ -> },
                    onSyncNow = {},
                    onOpenProfile = {}
                )
            }
        }

        composeRule.onNodeWithTag("chats_search_input").performTextInput("Zeta")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chats_search_count_badge").assertTextEquals("0")
        composeRule.onNodeWithTag("chats_search_empty_state").assertIsDisplayed()

        composeRule.onNodeWithTag("chats_empty_search_clear_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("conversation_card_conv_alice").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_card_conv_bob").assertIsDisplayed()
    }
}
