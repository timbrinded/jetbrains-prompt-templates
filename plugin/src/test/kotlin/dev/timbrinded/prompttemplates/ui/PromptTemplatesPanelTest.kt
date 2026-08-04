package dev.timbrinded.prompttemplates.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull

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
}
