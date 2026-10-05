package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.ComboBox
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
            val goal = form.control<JBTextField>("Goal")
            val notes = form.control<JBTextArea>("Notes")
            val mode = form.control<ComboBox<*>>("Mode")
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
            fun scroll(form: DynamicVariableForm) =
                assertNotNull(SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, form.control<JBTextArea>("Notes")))
            val changes = mutableListOf<String>()
            val large = form(8, changes)
            val small = form(2, mutableListOf())
            val area = large.control<JBTextArea>("Notes")
            assertEquals(8, area.rows)
            assertEquals("Enter notes", area.emptyText.text)
            assertTrue(scroll(large).preferredSize.height > scroll(small).preferredSize.height)
            large.control<JButton>("Reset Notes to Default").doClick()
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
            val label = form.descendants().filterIsInstance<JBLabel>().single { it.labelFor != null }
            assertSame(form.control<JBTextArea>("Notes"), label.labelFor)
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
            val row = generateSequence<Component>(form.control<JBTextField>("Issue")) { it.parent }
                .first { it.parent === form }

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

    private fun Container.descendants(): Sequence<Component> = components.asSequence().flatMap { child ->
        sequenceOf(child) + ((child as? Container)?.descendants() ?: emptySequence())
    }

    /** The control a screen reader announces as [accessibleName], as users of assistive technology find it. */
    private inline fun <reified T : JComponent> DynamicVariableForm.control(accessibleName: String): T =
        descendants().filterIsInstance<T>().single { it.accessibleContext.accessibleName == accessibleName }
}
