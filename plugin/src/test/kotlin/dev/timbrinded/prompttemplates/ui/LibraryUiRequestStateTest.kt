package dev.timbrinded.prompttemplates.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryUiRequestStateTest {
    @Test
    fun `author callbacks reject stale requests and retain destinations`() {
        val tracker = AuthorAsyncRequestTracker()
        val save = requireNotNull(tracker.beginSave(Path.of("library", "original")))
        tracker.invalidate()
        assertFalse(tracker.isCurrent(save))

        val firstDestination = Path.of("library", "First")
        val secondDestination = Path.of("library", "Second")
        val first = tracker.begin(firstDestination)
        val second = tracker.begin(secondDestination)
        assertEquals(firstDestination, first.destination)
        assertEquals(secondDestination, second.destination)
        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))
    }

    @Test
    fun `a second save is rejected while the first save is in progress`() {
        val tracker = AuthorAsyncRequestTracker()
        val nestedDestination = Path.of("library", "Reviews", "Security")

        val first = requireNotNull(tracker.beginSave(nestedDestination))
        val second = tracker.beginSave(Path.of("library"))

        assertEquals(nestedDestination, first.destination)
        assertNull(second)
        tracker.finishSave(first)
        assertEquals(nestedDestination, tracker.beginSave(nestedDestination)?.destination)
    }
}
