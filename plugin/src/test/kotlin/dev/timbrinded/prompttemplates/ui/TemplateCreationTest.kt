package dev.timbrinded.prompttemplates.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateCreationTest {
    @Test
    fun `suggestion skips occupied visible names and leaves repository validation authoritative`() {
        assertEquals("Review copy", availableTemplateName("Review copy", listOf("Review")))
        assertEquals("Review copy (4)", availableTemplateName("Review copy",
            listOf(" REVIEW COPY ", "Review copy (2)", "review copy (3)", "Review copy (5)")))
        assertEquals("Selection from source.kt (2)", availableTemplateName("Selection from source.kt", listOf("Selection from source.kt")))
        assertEquals("i copy (2)", availableTemplateName("i copy", listOf("İ copy")))
    }
}
