package dev.timbrinded.prompttemplates.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class EnumOptionInputTest {
    @Test
    fun `semicolon separates literal enum choices`() {
        val options = parseEnumOptionInput("huge-icons; lucide-react-icons")

        assertEquals(listOf("huge-icons", "lucide-react-icons"), options.map { it.label })
        assertEquals(options.map { it.label }, options.map { it.value })
    }
}
