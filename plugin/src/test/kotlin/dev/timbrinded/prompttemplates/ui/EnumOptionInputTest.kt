package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption
import kotlin.test.Test
import kotlin.test.assertEquals

class EnumOptionInputTest {
    @Test
    fun `editing choices keeps surviving identities and avoids assigning them to new labels`() {
        val old = listOf(EnumOption("deep-id", "Deep", "Deep"), EnumOption("quick", "Brief", "Brief"))
        val options = parseEnumOptionInput("Quick; Deep; Brief", old)
        assertEquals(listOf("quick-2", "deep-id", "quick"), options.map(EnumOption::id))
    }

    @Test
    fun `semicolon separates literal enum choices`() {
        val options = parseEnumOptionInput("huge-icons; lucide-react-icons")

        assertEquals(listOf("huge-icons", "lucide-react-icons"), options.map { it.label })
        assertEquals(options.map { it.label }, options.map { it.value })
    }
}
