package dev.timbrinded.prompttemplates.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryFileWatcherTest {
    private val root = Path.of("/library").toAbsolutePath().normalize()

    @Test
    fun `reacts to canonical template files and template directories`() {
        assertTrue(isPromptLibraryChange(root, "/library/review/prompt.md"))
        assertTrue(isPromptLibraryChange(root, "/library/review/prompt.meta.json"))
        assertTrue(isPromptLibraryChange(root, "/library/review"))
    }

    @Test
    fun `ignores temporary files nested extras and sibling directories`() {
        assertFalse(isPromptLibraryChange(root, "/library/review/.prompt.md.123.tmp"))
        assertFalse(isPromptLibraryChange(root, "/library/review/notes.txt"))
        assertFalse(isPromptLibraryChange(root, "/library-backup/review/prompt.md"))
    }
}
