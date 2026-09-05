package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceholderParserTest {
    private val parser = LinearPlaceholderParser()

    @Test
    fun `literal capture round trips escaped malformed and overlapping openings through rendering`() {
        val renderer = StrictPromptRenderer(parser)
        val selections = listOf(
            "", "  \t\r\n", "猫 {{name}} 🦧", "{{ide.selection}} {{clipboard}}",
            "\\{{already_escaped}}", "\\\\{{two_backslashes}}", "{{", "{{bad key}}",
            "{{{name}}}", "{{{{", "{{outer {{inner}}", "\\{{{{{\\{{end}}",
        )
        for (selection in selections) {
            val draft = PromptTemplateDraft(name = "Captured text", markdown = escapePlaceholderOpenings(selection))
            val rendered = renderer.render(draft.toTemplate(), emptyMap(), emptyMap())
            assertTrue(rendered.isValid, rendered.diagnostics.toString())
            assertEquals(selection, rendered.renderedText)
            assertTrue(parser.parse(draft.markdown).placeholders.isEmpty())
        }
    }

    @Test
    fun `parses user and context placeholders with whitespace`() {
        val result = parser.parse("Hello {{ name }} from {{ide.project.name}} and {{repeat}} {{repeat}}")

        assertEquals(listOf("name", "ide.project.name", "repeat", "repeat"), result.placeholders.map { it.key })
        assertEquals(listOf("name", "ide.project.name", "repeat"), result.referencedKeys)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `escaped opening is literal and not a placeholder`() {
        val result = parser.parse("Use \\{{literal}} then {{actual}}")

        assertEquals(listOf("actual"), result.referencedKeys)
        assertEquals(1, result.escapedOpenings.size)
    }

    @Test
    fun `reports malformed placeholders without throwing`() {
        val result = parser.parse("{{bad key}} and {{unfinished")

        assertEquals(2, result.diagnostics.size)
        assertTrue(result.placeholders.isEmpty())
    }

    @Test
    fun `handles unicode around ASCII identifiers`() {
        val result = parser.parse("Εξέτασε {{objective}} — 猫")

        assertEquals("objective", result.placeholders.single().key)
    }
}
