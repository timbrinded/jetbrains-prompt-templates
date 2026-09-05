package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DynamicVariableFormTest {
    @Test
    fun `multiline label identifies the editable input rather than its scroll pane`() {
        SwingUtilities.invokeAndWait {
            val form = DynamicVariableForm(
                variables = listOf(PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE)),
                accents = emptyMap(), values = emptyMap(), onChanged = { _, _ -> },
            )
            val row = assertIs<JPanel>(form.components.first())
            val label = row.components.filterIsInstance<JBLabel>().single()
            val scroll = row.components.filterIsInstance<JBScrollPane>().single()
            assertSame(assertIs<JBTextArea>(scroll.viewport.view), label.labelFor)
        }
    }

    @Test
    fun `text variable remains a single-line row in a tall form`() {
        SwingUtilities.invokeAndWait {
            val form = DynamicVariableForm(
                variables = listOf(PromptVariable("issue", "Issue", description = "The GH issue number")),
                accents = emptyMap(),
                values = mutableMapOf(),
                onChanged = { _, _ -> },
            )
            form.setSize(500, 300)
            form.doLayout()
            val row = assertIs<JPanel>(form.components.first())

            assertEquals(row.preferredSize.height, row.height)
        }
    }

    @Test
    fun `enum choices contain no empty selection`() {
        val variable = PromptVariable(
            key = "density",
            label = "Density",
            type = PromptVariableType.ENUM,
            options = listOf(
                EnumOption("low", "Low", "Low"),
                EnumOption("high", "High", "High"),
            ),
        )

        assertEquals(listOf("low", "high"), enumChoices(variable).map(EnumChoice::id))
    }
}
