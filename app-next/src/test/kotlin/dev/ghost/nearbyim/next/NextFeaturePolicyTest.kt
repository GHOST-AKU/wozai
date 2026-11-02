package dev.ghost.nearbyim.next

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ghost.nearbyim.core.*
import org.junit.Assert.*
import org.junit.Test

class NextFeaturePolicyTest {
    @Test fun cursorInsertionReplacesSelectionAndKeepsSuffix() {
        val result = insertEmoji(TextFieldValue("before SELECT after", TextRange(7, 13)), "👩🏽‍💻")
        assertEquals("before 👩🏽‍💻 after", result.text)
        assertEquals(TextRange(7 + "👩🏽‍💻".length), result.selection)
        assertEquals("a😀b", insertEmoji(TextFieldValue("ab", TextRange(1)), "😀").text)
    }
    @Test fun checkingUsesCheckedBytesRatherThanTransferredBytes() {
        val info = AttachmentInfo.v2("01234567-1234-1234-1234-0123456789ab", "fixture", "text/plain", 1000, null, 0)
        val record = AttachmentRecord(info, true, "checking", 800)
        assertNull(transferFraction(record, TransferProgress.UNKNOWN))
        assertEquals(.25f, transferFraction(record, TransferProgress(0, -1, 250, 1000))!!, .0001f)
        assertEquals(1f, transferFraction(record, TransferProgress(0, -1, 0, 0))!!, .0001f)
    }
    @Test fun selectionMustAgreeWithVisibleDraftAndController() {
        assertTrue(draftSelectionCurrent("alice", "alice", "alice", false))
        assertFalse(draftSelectionCurrent("alice", "alice", "bob", false))
        assertFalse(draftSelectionCurrent("alice", "bob", "bob", false))
        assertFalse(draftSelectionCurrent(null, null, null, false))
        assertFalse(draftSelectionCurrent("alice", "alice", "alice", true))
    }
    @Test fun saveAndConnectionTransitionsBypassTransferThrottle() {
        val visible = NextState(selectedId = "alice", connectedId = "alice", connected = true)
        assertFalse(observerNeedsImmediateRefresh(visible, "alice", "alice", true, false, false, false))
        assertTrue(observerNeedsImmediateRefresh(visible, "alice", "alice", true, false, false, true))
        assertTrue(observerNeedsImmediateRefresh(visible, "alice", "alice", true, false, true, false))
        assertTrue(observerNeedsImmediateRefresh(visible, "bob", "alice", true, false, false, false))
        assertTrue(observerNeedsImmediateRefresh(visible, "alice", null, false, false, false, false))
        assertTrue(observerNeedsImmediateRefresh(visible, "alice", "alice", true, true, false, false))
    }

}
