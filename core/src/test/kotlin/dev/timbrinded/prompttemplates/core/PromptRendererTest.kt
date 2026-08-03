package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PromptRendererTest {
    private val renderer = StrictPromptRenderer()

    @Test
    fun `renders text multiline enum and context preserving whitespace`() {
        val template = template(
            markdown = "Goal: {{goal}}\nDepth: {{depth}}\n\n{{details}}\n{{ide.selection}}",
            variables = listOf(
                PromptVariable("goal", "Goal"),
                PromptVariable(
                    "depth",
                    "Depth",
                    PromptVariableType.ENUM,
                    options = listOf(EnumOption("deep", "Deep", "Review every failure mode.")),
                ),
                PromptVariable("details", "Details", PromptVariableType.MULTILINE),
            ),
        )

        val result = renderer.render(
            template,
            mapOf("goal" to "Correctness", "depth" to "deep", "details" to "Line one\nLine two"),
            mapOf("ide.selection" to ContextValue.available("const answer = 42")),
        )

        assertTrue(result.isValid)
        assertEquals(
            "Goal: Correctness\nDepth: Review every failure mode.\n\nLine one\nLine two\nconst answer = 42",
            result.renderedText,
        )
    }

    @Test
    fun `does not recursively render values`() {
        val result = renderer.render(
            template("{{value}}", listOf(PromptVariable("value", "Value"))),
            mapOf("value" to "{{second}}"),
            emptyMap(),
        )

        assertEquals("{{second}}", result.renderedText)
        assertTrue(result.isValid)
    }

    @Test
    fun `missing required value blocks output while retaining placeholder in preview`() {
        val result = renderer.render(
            template("Before {{value}} after", listOf(PromptVariable("value", "Value"))),
            emptyMap(),
            emptyMap(),
        )

        assertFalse(result.isValid)
        assertEquals("Before {{value}} after", result.renderedText)
        assertIs<TemplateDiagnostic.MissingRequiredValue>(result.diagnostics.single())
    }

    @Test
    fun `optional empty value and escaped opening render exactly`() {
        val result = renderer.render(
            template("A{{optional}}B \\{{literal}}", listOf(PromptVariable("optional", "Optional", required = false))),
            emptyMap(),
            emptyMap(),
        )

        assertTrue(result.isValid)
        assertEquals("AB {{literal}}", result.renderedText)
    }

    private fun template(markdown: String, variables: List<PromptVariable>) = PromptTemplate(
        TemplateMetadata(id = TemplateId.random().value, name = "Test", variables = variables),
        markdown,
    )
}
