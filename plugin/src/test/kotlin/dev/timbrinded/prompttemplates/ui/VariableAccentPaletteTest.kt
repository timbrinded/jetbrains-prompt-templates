package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VariableAccentPaletteTest {
    @Test
    fun `assigns stable distinct accents in variable order`() {
        val variables = listOf(
            PromptVariable("density", "Density"),
            PromptVariable("language", "Language"),
            PromptVariable("font-icon", "Font Icon"),
        )

        val first = VariableAccentPalette.forVariables(variables)
        val second = VariableAccentPalette.forVariables(variables)

        assertEquals(first, second)
        assertNotEquals(first.getValue("density"), first.getValue("language"))
        assertNotEquals(first.getValue("language"), first.getValue("font-icon"))
    }
}
