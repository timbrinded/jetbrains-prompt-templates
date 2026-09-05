package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthorEditSnapshotTest {
    private val mode = PromptVariable(
        key = "mode", label = "Mode", type = PromptVariableType.ENUM,
        options = listOf(EnumOption("quick", "Quick", "Quick"), EnumOption("deep", "Deep", "Deep")),
        defaultValue = "quick",
    )
    private val notes = PromptVariable("notes", "Notes")
    private val initial = PromptTemplateDraft(
        name = "Review", description = "Describe", tags = listOf("review"),
        variables = listOf(mode, notes), markdown = "{{mode}} {{notes}}",
    )

    @Test
    fun `every persisted editable field participates and a complete revert is clean`() {
        val baseline = capture(initial)
        val edits = listOf(
            initial.copy(name = "Changed"),
            initial.copy(description = "Changed"),
            initial.copy(tags = listOf("changed")),
            initial.copy(markdown = "Changed"),
            initial.copy(variables = listOf(mode.copy(defaultValue = "deep"), notes)),
            initial.copy(variables = listOf(mode.copy(options = mode.options.reversed()), notes)),
            initial.copy(variables = listOf(mode, notes.copy(type = PromptVariableType.MULTILINE))),
            initial.copy(variables = listOf(mode, notes.copy(label = "Changed", description = "Help"))),
        )
        edits.forEach { changed ->
            assertNotEquals(baseline, capture(changed))
            assertEquals(baseline, capture(initial))
        }
    }

    @Test
    fun `inspector selection is visual but pending raw inputs require confirmation`() {
        val baseline = capture(initial)
        assertEquals(baseline, AuthorEditSnapshot.capture(initial, "review", notes, "notes", ""))
        assertNotEquals(baseline, AuthorEditSnapshot.capture(initial, "review", mode, "unapplied_key", "Quick; Deep"))
        assertNotEquals(baseline, AuthorEditSnapshot.capture(initial, "review", mode, "mode", "Quick;Deep"))
        assertNotEquals(baseline, AuthorEditSnapshot.capture(initial, "review, ", mode, "mode", "Quick; Deep"))
    }

    private fun capture(draft: PromptTemplateDraft) = AuthorEditSnapshot.capture(draft, "review", mode, "mode", "Quick; Deep")
}
