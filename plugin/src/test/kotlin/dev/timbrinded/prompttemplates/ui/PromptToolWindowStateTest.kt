package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.StoredTemplate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptToolWindowStateTest {
    @Test
    fun `library root change preserves the draft but rebases an existing edit as a new template`() {
        val oldRoot = Path.of("/libraries/old-library")
        val newRoot = Path.of("/libraries/new-library")
        val existing = StoredTemplate(
            PromptTemplateDraft(name = "Draft", markdown = "version one").toTemplate(),
            oldRoot.resolve("reviews/draft"),
        )
        val author = TemplateAuthorState(
            draft = PromptTemplateDraft(name = "Draft", markdown = "version one"),
            existing = existing,
            destination = oldRoot.resolve("reviews"),
        )

        val rebased = author.rebasedAsNewTemplate(newRoot)

        assertNull(rebased.existing)
        assertEquals(newRoot.toAbsolutePath().normalize(), rebased.destination)
        assertEquals(rebased, rebased.rebasedAsNewTemplate(newRoot.resolve(".")))
    }
}
