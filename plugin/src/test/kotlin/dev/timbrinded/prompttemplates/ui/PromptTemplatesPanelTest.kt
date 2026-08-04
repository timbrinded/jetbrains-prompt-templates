package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplatesPanelTest {
    @Test
    fun `library refresh does not replace an open new-template draft with the prior selection`() {
        val previouslySelected = Path.of("library", "existing-prompt")

        val selected = selectDirectoryAfterLibraryReload(
            activeDirectory = null,
            selectedDirectory = previouslySelected,
            authorOpen = true,
        )

        assertNull(selected)
    }

    @Test
    fun `saving a template refreshes and selects its stored directory`() {
        val savedDirectory = Path.of("library", "new-prompt")
        val events = mutableListOf<String>()

        afterTemplateSaved(
            savedDirectory = savedDirectory,
            showSaved = { events += "show" },
            refreshLibrary = { selected -> events += "refresh:$selected" },
        )

        assertEquals(listOf("show", "refresh:$savedDirectory"), events)
    }

    @Test
    fun `deleting a template clears selection and refreshes the library`() {
        val events = mutableListOf<String>()

        afterTemplateDeleted(
            clearSelection = { events += "clear" },
            refreshLibrary = { selected -> events += "refresh:$selected" },
        )

        assertEquals(listOf("clear", "refresh:null"), events)
    }

    @Test
    fun `a failed load discards a summary when its directory is gone`() {
        val result: RepositoryResult<StoredTemplate> = RepositoryResult.Failure("Template directory does not exist.")

        assertTrue(shouldDiscardTemplateSummary(result, directoryMissing = true))
    }
}
