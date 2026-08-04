package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.OutputMapping
import dev.timbrinded.prompttemplates.core.RenderResult
import dev.timbrinded.prompttemplates.core.SourceRange
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderedVariableHighlightControllerTest {
    @Test
    fun `maps each substituted user value to its variable accent`() {
        val accents = VariableAccentPalette.forVariables(
            listOf(
                dev.timbrinded.prompttemplates.core.PromptVariable("density", "Density"),
                dev.timbrinded.prompttemplates.core.PromptVariable("language", "Language"),
            ),
        )
        val result = RenderResult(
            renderedText = "A high prompt in ASD STE 100",
            diagnostics = emptyList(),
            mappings = listOf(
                OutputMapping(SourceRange(0, 11), SourceRange(2, 6), "density"),
                OutputMapping(SourceRange(22, 34), SourceRange(17, 28), "language"),
                OutputMapping(SourceRange(35, 46), SourceRange(29, 33), "density"),
                OutputMapping(SourceRange(35, 52), SourceRange(28, 28), "optional"),
                OutputMapping(SourceRange(53, 70), SourceRange(0, 1), "ide.selection"),
            ),
        )

        val highlights = renderedVariableHighlights(result, accents)

        assertEquals(listOf("density", "language", "density"), highlights.map { it.key })
        assertEquals(
            listOf(SourceRange(2, 6), SourceRange(17, 28), SourceRange(29, 33)),
            highlights.map { it.range },
        )
        assertEquals(accents.getValue("density"), highlights.first().accent)
        assertEquals(highlights.first().accent, highlights.last().accent)
    }

    @Test
    fun `uses clean inline emphasis without a token outline`() {
        val accent = VariableAccentPalette.forVariables(
            listOf(dev.timbrinded.prompttemplates.core.PromptVariable("density", "Density")),
        ).getValue("density")

        val attributes = renderedVariableTextAttributes(accent)

        assertEquals(accent.foreground, attributes.foregroundColor)
        assertEquals(accent.background, attributes.backgroundColor)
        assertNull(attributes.effectType)
        assertNull(attributes.effectColor)
        assertEquals(Font.BOLD, attributes.fontType)
    }
}
