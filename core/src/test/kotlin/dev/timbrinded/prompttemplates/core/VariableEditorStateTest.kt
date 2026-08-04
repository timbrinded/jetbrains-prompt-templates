package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableEditorStateTest {
    @Test
    fun `transient discoveries replace partial keys while typing`() {
        val state = VariableEditorState(emptyList())

        state.reconcile("{{la}}")
        state.reconcile("{{lan}}")
        state.reconcile("{{language}}")

        assertEquals(listOf("language"), state.variables.map(PromptVariable::key))
    }

    @Test
    fun `editing a transient variable makes it persistent`() {
        val state = VariableEditorState(emptyList())
        state.reconcile("{{lang}}")
        state.updateAt(0) { it.copy(label = "Language choice") }

        val result = state.reconcile("{{language}}")

        assertEquals(listOf("language", "lang"), state.variables.map(PromptVariable::key))
        assertEquals(setOf("lang"), result.unusedKeys)
    }

    @Test
    fun `saved variables remain persistent when their placeholders are removed`() {
        val state = VariableEditorState(
            listOf(PromptVariable(key = "language", label = "Language")),
        )

        val result = state.reconcile("No variables")

        assertEquals(listOf("language"), state.variables.map(PromptVariable::key))
        assertEquals(setOf("language"), result.unusedKeys)
    }

    @Test
    fun `changing to enum creates a required selected choice`() {
        val state = VariableEditorState(
            listOf(PromptVariable(key = "language", label = "Language", required = false)),
        )

        state.changeTypeAt(0, PromptVariableType.ENUM)

        val variable = state.variables.single()
        assertEquals(PromptVariableType.ENUM, variable.type)
        assertTrue(variable.required)
        assertEquals(listOf("Option"), variable.options.map(EnumOption::label))
        assertEquals(variable.options.single().id, variable.defaultValue)
    }

    @Test
    fun `changing from enum removes enum-only state`() {
        val state = VariableEditorState(
            listOf(
                PromptVariable(
                    key = "language",
                    label = "Language",
                    type = PromptVariableType.ENUM,
                    defaultValue = "plain",
                    options = listOf(EnumOption("plain", "Plain", "Plain")),
                ),
            ),
        )

        state.changeTypeAt(0, PromptVariableType.MULTILINE)

        val variable = state.variables.single()
        assertEquals(PromptVariableType.MULTILINE, variable.type)
        assertEquals(null, variable.defaultValue)
        assertEquals(emptyList(), variable.options)
    }

    @Test
    fun `changing between text types keeps their default`() {
        val state = VariableEditorState(
            listOf(PromptVariable(key = "objective", label = "Objective", defaultValue = "Review it")),
        )

        state.changeTypeAt(0, PromptVariableType.MULTILINE)

        assertEquals("Review it", state.variables.single().defaultValue)
    }
}
