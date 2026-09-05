package dev.timbrinded.prompttemplates.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContextAttachmentsTest {
    private fun item(id: String, text: String) = ContextAttachment(id, "$id.txt", "/project/$id.txt — editor buffer (unsaved)", "2026-09-05T12:00:00Z", text)

    @Test
    fun `frozen items retain order while replacement and removal change only the selected item`() {
        val mutable = mutableListOf(item("a", "first"), item("b", "second"))
        val original = ContextAttachments(mutable)
        mutable.clear()
        val replaced = original.with(listOf(item("b", "updated"), item("c", "third")))
        assertEquals(listOf("a", "b", "c"), replaced.items.map { it.id })
        assertEquals(listOf("first", "updated", "third"), replaced.items.map { it.text })
        assertEquals(listOf("a", "c"), replaced.without("b").items.map { it.id })
        assertEquals(listOf("first", "second"), original.items.map { it.text })
    }

    @Test
    fun `attachment text is literal inside a longer fence and is substituted only once`() {
        val source = "  {{goal}}\n`````\ntrailing  "
        val attachments = ContextAttachments(listOf(item("literal", source)))
        val context = attachments.contextValue()
        assertTrue(context.value!!.contains("``````\n$source\n``````"))
        val template = PromptTemplateDraft(name = "Literal", markdown = "{{ide.attachments}}\n{{goal}}",
            variables = listOf(PromptVariable("goal", "Goal", defaultValue = "Final goal"))).toTemplate()
        val rendered = StrictPromptRenderer().render(template, emptyMap(), mapOf(ATTACHMENTS_CONTEXT_KEY to context))
        assertTrue(rendered.isValid)
        assertEquals(context.value + "\nFinal goal", rendered.renderedText)
        assertTrue(rendered.renderedText.contains("{{goal}}"))
    }

    @Test
    fun `limits count UTF8 bytes and reject the entire new set without truncation`() {
        val original = ContextAttachments(listOf(item("original", "retained")))
        assertFailsWith<IllegalArgumentException> { original.with(listOf(item("large", "é".repeat(MAX_ATTACHMENT_BYTES / 2 + 1)))) }
        assertEquals("retained", original.items.single().text)
        val boundary = item("boundary", "x".repeat(MAX_ATTACHMENT_BYTES))
        assertEquals(MAX_ATTACHMENT_BYTES, ContextAttachments(listOf(boundary)).byteCount)
        assertFailsWith<IllegalArgumentException> { ContextAttachments((0..MAX_ATTACHMENTS).map { item("$it", "") }) }
        val total = ContextAttachments((0..3).map { item("$it", boundary.text) })
        assertEquals(MAX_ATTACHMENTS_BYTES, total.byteCount)
        assertFailsWith<IllegalArgumentException> { total.with(listOf(item("extra", "a"))) }
    }

    @Test
    fun `removing the last attachment makes required context unavailable but empty files are valid`() {
        val attachments = ContextAttachments(listOf(item("empty", "")))
        assertEquals(ContextStatus.AVAILABLE, attachments.contextValue().status)
        assertEquals(ContextStatus.UNAVAILABLE, attachments.without("empty").contextValue().status)
    }
}
