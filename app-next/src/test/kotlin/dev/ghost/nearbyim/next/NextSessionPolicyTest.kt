package dev.ghost.nearbyim.next

import org.junit.Assert.*
import org.junit.Test

class NextSessionPolicyTest {
    @Test fun selectionCannotCrossPeerOrReconnectedSession() {
        val selection = AttachmentSelectionSession("alice", 7, "session-a")
        assertTrue(selection.accepts("alice", 7, "session-a", true))
        assertFalse(selection.accepts("bob", 7, "session-a", true))
        assertFalse(selection.accepts("alice", 8, "session-a", true))
        assertFalse(selection.accepts("alice", 7, "session-b", true))
        assertFalse(selection.accepts("alice", 7, "session-a", false))
    }

    @Test fun completionPreservesNewTypingAndOtherConversations() {
        assertEquals("", draftAfterSave("alice", "hello", "alice", "hello"))
        assertEquals("hello again", draftAfterSave("alice", "hello again", "alice", "hello"))
        assertEquals("hello", draftAfterSave("bob", "hello", "alice", "hello"))
    }

    @Test fun utf8LimitUsesBytesWithoutTrimmingUserText() {
        assertTrue(canSubmitText("你".repeat(2730), 8192, true, false))
        assertFalse(canSubmitText("你".repeat(2731), 8192, true, false))
        assertFalse(canSubmitText("hi\uD83D", 8192, true, false))
        assertFalse(canSubmitText(" \n ", 8192, true, false))
        assertFalse(canSubmitText("hi", 8192, false, false))
        assertFalse(canSubmitText("hi", 8192, true, true))
    }

    @Test fun completionWaitsForMatchingDraftLoad() {
        assertFalse(shouldHandleSave("alice", "alice", true))
        assertTrue(shouldHandleSave("alice", "alice", false))
        assertTrue(shouldHandleSave("bob", "alice", true))
    }
}
