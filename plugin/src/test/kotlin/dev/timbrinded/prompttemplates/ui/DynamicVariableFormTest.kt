package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DynamicVariableFormTest {
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
