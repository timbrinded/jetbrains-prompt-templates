package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptVariableType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariableTypePresentationTest {
    @Test
    fun `enum only shows its choices`() {
        val presentation = variableTypePresentation(PromptVariableType.ENUM)

        assertFalse(presentation.requiredVisible)
        assertTrue(presentation.enumChoicesVisible)
    }

    @Test
    fun `text types only show required`() {
        listOf(PromptVariableType.TEXT, PromptVariableType.MULTILINE).forEach { type ->
            val presentation = variableTypePresentation(type)

            assertTrue(presentation.requiredVisible)
            assertFalse(presentation.enumChoicesVisible)
        }
    }
}
