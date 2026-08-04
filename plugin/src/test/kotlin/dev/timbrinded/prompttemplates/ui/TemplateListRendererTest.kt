package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSummary
import java.nio.file.Path
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TemplateListRendererTest {
    @Test
    fun `renders a template when no accessibility context is available`() {
        val renderer = TemplateListRenderer()
        val summary = TemplateSummary(
            id = null,
            name = "No variables",
            description = "A template without variables",
            tags = emptyList(),
            directory = Path.of("no-variables"),
            health = TemplateHealth.HEALTHY,
        )

        SwingUtilities.invokeAndWait {
            val component = renderer.getListCellRendererComponent(
                JList(arrayOf(summary)),
                summary,
                0,
                false,
                false,
            )

            assertSame(renderer, component)
            assertEquals(summary.description, renderer.toolTipText)
        }
    }
}
