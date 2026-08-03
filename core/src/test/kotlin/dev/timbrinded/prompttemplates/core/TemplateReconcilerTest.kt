package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateReconcilerTest {
    private val reconciler = TemplateReconciler()

    @Test
    fun `preserves metadata and adds new variables without deleting unused ones`() {
        val existing = listOf(
            PromptVariable("depth", "Review depth", PromptVariableType.ENUM, options = listOf(EnumOption("deep", "Deep", "Deep"))),
            PromptVariable("unused", "Unused", required = false),
        )

        val result = reconciler.reconcile("{{depth}} {{objective}} {{ide.selection}}", existing)

        assertEquals(listOf("depth", "objective", "unused"), result.variables.map(PromptVariable::key))
        assertEquals(PromptVariableType.ENUM, result.variables.first().type)
        assertEquals(setOf("unused"), result.unusedKeys)
        assertTrue(result.unknownContextKeys.isEmpty())
    }

    @Test
    fun `rename replaces all matching placeholders in one transformation`() {
        assertEquals(
            "{{new_key}} then {{other}} then {{new_key}}",
            reconciler.rename("{{old}} then {{other}} then {{ old }}", "old", "new_key"),
        )
    }
}
