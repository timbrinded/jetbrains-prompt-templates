package dev.timbrinded.prompttemplates.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptInvocationTest {
    @Test
    fun `captured clipboard stays stable when original provider data changes`() {
        val context = mutableMapOf("clipboard" to ContextValue.available("abc"))
        val invocation = PromptInvocation(stored("Review: {{clipboard}}"), emptyMap(), context)
        context["clipboard"] = ContextValue.available(invocation.render.renderedText)

        assertEquals("Review: abc", invocation.render.renderedText)
        assertEquals("Review: abc", invocation.withValue("unused", "value").render.renderedText)
    }

    @Test
    fun `input edits and reset retain context and restore authored defaults`() {
        val stored = stored("{{goal}}: {{clipboard}}", listOf(PromptVariable("goal", "Goal", defaultValue = "Review")))
        val invocation = PromptInvocation(stored, emptyMap(), mapOf("clipboard" to ContextValue.available("abc")))

        assertEquals("Explain: abc", invocation.withValue("goal", "Explain").render.renderedText)
        assertEquals("Review: abc", invocation.withValue("goal", "Explain").resetValues().render.renderedText)
        assertEquals("Review: abc", invocation.render.renderedText)
    }

    @Test
    fun `requested context ignores escaped openings user inputs and duplicate references`() {
        val stored = stored("\\{{clipboard}} {{goal}} {{ide.selection}} {{ide.selection}} {{ide.project.name}}")

        assertEquals(setOf("ide.selection", "ide.project.name"), requestedContextKeys(stored.template))
    }

    @Test
    fun `reload retains only compatible values and valid enum identities`() {
        val original = listOf(
            PromptVariable("goal", "Goal", defaultValue = "default"),
            PromptVariable("details", "Details"),
            PromptVariable("depth", "Depth", PromptVariableType.ENUM, options = listOf(EnumOption("brief", "Brief", "Brief"))),
            PromptVariable("removed", "Removed"),
        )
        val edited = listOf(
            original[0],
            original[1].copy(type = PromptVariableType.MULTILINE),
            original[2].copy(options = listOf(EnumOption("full", "Full", "Full"))),
        )
        val values = mapOf("goal" to "Keep", "details" to "Old type", "depth" to "brief", "removed" to "Gone")

        assertEquals(mapOf("goal" to "Keep"), compatibleInvocationValues(original, edited, values))
    }

    private fun stored(markdown: String, variables: List<PromptVariable> = emptyList()) = StoredTemplate(
        PromptTemplateDraft(name = "Snapshot", markdown = markdown, variables = variables).toTemplate(),
        Path.of("library/snapshot"),
    )
}
