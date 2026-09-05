package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorVariableEditsTest {
    @Test
    fun `insertion accepts complete tokens and rejects escaped or embedded positions`() {
        val markdown = "{{goal}} \\{{goal}} {{goal}} tail"
        assertTrue(canInsertPlaceholder(markdown, SourceRange(markdown.length, markdown.length), "goal"))
        assertTrue(canInsertPlaceholder(markdown, SourceRange(0, 8), "ide.selection"))
        assertFalse(canInsertPlaceholder(markdown, SourceRange(3, 3), "goal"))
        assertFalse(canInsertPlaceholder(markdown, SourceRange(10, 10), "goal"))
        assertFalse(canInsertPlaceholder(markdown, SourceRange(0, 0), "invalid key"))
        val updated = markdown + "{{goal}}"
        assertEquals(listOf("goal", "goal", "goal"), LinearPlaceholderParser().parse(updated).placeholders.map { it.key })
    }

    @Test
    fun `extraction keys must be valid unique user keys`() {
        for (key in listOf("", "2key", "invalid key", "ide.selection", "ide.future", "clipboard", "goal")) {
            assertTrue(userVariableKeyError(key, listOf("goal")) != null, key)
        }
        assertNull(userVariableKeyError("task_notes-2", listOf("goal")))
    }

    @Test
    fun `extraction undo and redo retain exact default and unrelated later inspector edits`() {
        val state = VariableEditorState(listOf(PromptVariable("other", "Other")))
        val selected = "  first\nsecond  "
        val definition = PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE, defaultValue = selected)
        val change = VariableDefinitionChange(state, 1, null, definition)
        change.redo()
        state.reconcile("{{other}} {{notes}}")
        state.updateAt(0) { it.copy(label = "Later other label") }
        state.updateAt(1) { it.copy(placeholder = "Later notes hint") }
        state.reconcile("{{other}} $selected") // Document undo precedes the schema callback.
        change.undo()
        assertEquals(listOf("other"), state.variables.map { it.key })
        assertEquals("Later other label", state.variables.single().label)
        change.redo()
        state.reconcile("{{other}} {{notes}}")
        assertEquals(selected, state.variables.last().defaultValue)
        assertEquals("Later notes hint", state.variables.last().placeholder)
        assertEquals("Later other label", state.variables.first().label)
    }

    @Test
    fun `rename and extraction undo together remove transient counterparts and redo repeated tokens`() {
        val state = VariableEditorState(emptyList())
        val original = PromptVariable("notes", "Notes", defaultValue = "literal")
        val extract = VariableDefinitionChange(state, 0, null, original)
        extract.redo()
        state.reconcile("{{notes}} {{notes}} \\{{notes}}")
        val rename = VariableDefinitionChange(state, 0, "notes", original.copy(key = "task"))
        rename.redo()
        val renamed = TemplateReconciler().rename("{{notes}} {{notes}} \\{{notes}}", "notes", "task")
        assertEquals("{{task}} {{task}} \\{{notes}}", renamed)
        state.reconcile(renamed)
        state.reconcile("{{notes}} {{notes}} \\{{notes}}")
        rename.undo()
        state.reconcile("{{notes}} {{notes}} \\{{notes}}")
        assertEquals(listOf(original), state.variables)
        state.reconcile("literal")
        extract.undo()
        assertTrue(state.variables.isEmpty())
        extract.redo()
        state.reconcile("{{notes}} {{notes}} \\{{notes}}")
        rename.redo()
        state.reconcile(renamed)
        assertEquals(listOf(original.copy(key = "task")), state.variables)
    }
}
