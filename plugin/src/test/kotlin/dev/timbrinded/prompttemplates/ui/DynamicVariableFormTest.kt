package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.ComboBox
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicVariableFormTest {
    @Test
    fun `session updates change controls without feedback or disturbing unchanged text selections`() {
        SwingUtilities.invokeAndWait {
            val changes = mutableListOf<Pair<String, String>>()
            val variables = listOf(
                PromptVariable("goal", "Goal", defaultValue = "Default"),
                PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE),
                PromptVariable("mode", "Mode", type = PromptVariableType.ENUM, defaultValue = "quick",
                    options = listOf(EnumOption("quick", "Quick", "Quick"), EnumOption("deep", "Deep", "Deep"))),
            )
            val values = mapOf("goal" to "Typed goal", "notes" to "Typed notes", "mode" to "quick")
            val form = DynamicVariableForm(variables, emptyMap(), values) { key, value -> changes.add(key to value) }
            val rows = form.components.filterIsInstance<JPanel>()
            val goal = rows[0].components.filterIsInstance<JBTextField>().single()
            val notes = assertIs<JBTextArea>(rows[1].components.filterIsInstance<JBScrollPane>().single().viewport.view)
            val mode = rows[2].components.filterIsInstance<ComboBox<*>>().single()
            notes.select(2, 5)
            form.updateValues(values + ("goal" to "Shared goal") + ("mode" to "deep"))
            assertEquals("Shared goal", goal.text)
            assertEquals("deep", assertIs<EnumChoice>(mode.selectedItem).id)
            assertEquals(2, notes.selectionStart)
            assertEquals(5, notes.selectionEnd)
            form.updateValues(values + ("notes" to "Shared\nnotes"))
            assertEquals("Shared\nnotes", notes.text)
            form.updateValues(emptyMap())
            assertEquals("Default", goal.text)
            assertEquals("", notes.text)
            assertEquals("quick", assertIs<EnumChoice>(mode.selectedItem).id)
            assertTrue(changes.isEmpty())
            notes.text = "User edit"
            assertEquals("notes" to "User edit", changes.last())
        }
    }

    @Test
    fun `multiline sizing placeholder and field reset use the authored settings`() {
        SwingUtilities.invokeAndWait {
            fun form(rows: Int, changes: MutableList<String>) = DynamicVariableForm(
                listOf(PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE,
                    defaultValue = "Authored\ndefault", minimumRows = rows, placeholder = "Enter notes")),
                emptyMap(), mapOf("notes" to "Session input"), { _, value -> changes.add(value) },
            )
            fun scroll(form: DynamicVariableForm) = (form.components.first() as JPanel).components.filterIsInstance<JBScrollPane>().single()
            val changes = mutableListOf<String>()
            val large = form(8, changes)
            val small = form(2, mutableListOf())
            val area = assertIs<JBTextArea>(scroll(large).viewport.view)
            assertEquals(8, area.rows)
            assertEquals("Enter notes", area.emptyText.text)
            assertTrue(scroll(large).preferredSize.height > scroll(small).preferredSize.height)
            val row = assertIs<JPanel>(large.components.first())
            row.components.filterIsInstance<JPanel>().single().components.filterIsInstance<JButton>().single().doClick()
            assertEquals("Authored\ndefault", area.text)
            assertEquals("Authored\ndefault", changes.last())
        }
    }

    @Test
    fun `multiline label identifies the editable input rather than its scroll pane`() {
        SwingUtilities.invokeAndWait {
            val form = DynamicVariableForm(
                variables = listOf(PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE)),
                accents = emptyMap(), values = emptyMap(), onChanged = { _, _ -> },
            )
            val row = assertIs<JPanel>(form.components.first())
            val label = row.components.filterIsInstance<JPanel>().single().components.filterIsInstance<JBLabel>().single()
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
