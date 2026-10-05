package dev.timbrinded.prompttemplates.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTreeOperationsTest {
    @Test
    fun `a selection is unchanged while it names the same entry`() {
        val template = LibrarySelectionKey.Template("6f1c1c0e-9f4b-4a52-9a43-1d5f3c2e7a10", "Reviews/review")

        // The invocation can re-key the selected template without its path; that is not a new user selection.
        assertTrue(isSameLibrarySelection(template, LibrarySelectionKey.Template(template.templateId.uppercase())))
        assertTrue(isSameLibrarySelection(null, null))
        assertTrue(isSameLibrarySelection(LibrarySelectionKey.Folder("Reviews"), LibrarySelectionKey.Folder("Reviews")))

        assertFalse(isSameLibrarySelection(template, LibrarySelectionKey.Template("0d6e5a8b-1c47-4f0e-8a3b-5b2a9f6c4d21")))
        assertFalse(isSameLibrarySelection(template, LibrarySelectionKey.TemplatePath("Reviews/review")))
        assertFalse(isSameLibrarySelection(LibrarySelectionKey.Folder("Reviews"), null))
    }
}
