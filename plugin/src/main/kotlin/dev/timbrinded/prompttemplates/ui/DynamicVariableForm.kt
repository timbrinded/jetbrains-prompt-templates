package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.initialValue
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

internal class DynamicVariableForm(
    private val variables: List<PromptVariable>,
    private val accents: Map<String, VariableAccent>,
    private val values: Map<String, String>,
    private val onChanged: (String, String) -> Unit,
) : JPanel() {
    private val controls = mutableMapOf<String, JComponent>()
    private var updatingValues = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
        isOpaque = false

        variables.forEach { variable ->
            val row = createVariableRow(variable)
            row.alignmentX = Component.LEFT_ALIGNMENT
            add(row)
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }
    }

    fun focusVariable(key: String) {
        controls[key]?.requestFocusInWindow()
    }

    fun updateValues(values: Map<String, String>) {
        // Session updates must not feed back through listeners or replace the document being typed in.
        updatingValues = true
        try {
            for (variable in variables) {
                val value = values[variable.key] ?: variable.initialValue()
                when (val input = controls.getValue(variable.key)) {
                    is JTextComponent -> if (input.text != value) input.text = value
                    is ComboBox<*> -> if ((input.selectedItem as? EnumChoice)?.id != value) {
                        input.selectedItem = enumChoices(variable).firstOrNull { it.id == value }
                    }
                }
            }
        } finally {
            updatingValues = false
        }
    }

    private fun createVariableRow(variable: PromptVariable): JPanel {
        val panel = object : JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(4))) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        panel.isOpaque = false
        val suffix = if (variable.required && variable.type != PromptVariableType.ENUM) " *" else ""
        val label = JBLabel(variable.label + suffix).setCopyable(true).setAllowAutoWrapping(true)
        accents[variable.key]?.let { accent ->
            panel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, accent.foreground),
                JBUI.Borders.emptyLeft(8),
            )
            label.foreground = accent.foreground
            label.font = label.font.deriveFont(label.font.style or Font.BOLD)
        }

        val control: JComponent = when (variable.type) {
            PromptVariableType.TEXT -> textField(variable)
            PromptVariableType.MULTILINE -> multilineField(variable)
            PromptVariableType.ENUM -> enumField(variable)
        }
        val input = controls.getValue(variable.key)
        label.labelFor = input
        val reset = JButton("Reset").apply {
            accessibleContext.accessibleName = "Reset ${variable.label} to Default"
            toolTipText = "Restore the authored default without refreshing context."
            addActionListener {
                val value = variable.initialValue()
                when (input) {
                    is JBTextField -> input.text = value
                    is JBTextArea -> input.text = value
                    is ComboBox<*> -> input.selectedItem = enumChoices(variable).firstOrNull { it.id == value }
                }
                input.requestFocusInWindow()
            }
        }
        panel.add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(label, BorderLayout.CENTER)
            add(reset, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                scrollRectToVisible(SwingUtilities.convertRectangle(control, Rectangle(control.size), this@DynamicVariableForm))
            }
        })
        panel.add(control, BorderLayout.CENTER)

        variable.description?.takeIf(String::isNotBlank)?.let { description ->
            val help = JBLabel(description).setCopyable(true).setAllowAutoWrapping(true)
            help.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            panel.add(help, BorderLayout.SOUTH)
        }
        return panel
    }

    private fun textField(variable: PromptVariable): JBTextField {
        val initial = currentValue(variable)
        val field = JBTextField(initial)
        field.emptyText.text = variable.placeholder.orEmpty()
        field.accessibleContext.accessibleName = variable.label
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (!updatingValues) onChanged(variable.key, field.text)
            }
        })
        controls[variable.key] = field
        return field
    }

    private fun multilineField(variable: PromptVariable): JBScrollPane {
        val area = JBTextArea(currentValue(variable))
        area.rows = variable.minimumRows ?: 4
        area.columns = 24
        area.emptyText.text = variable.placeholder.orEmpty()
        area.lineWrap = true
        area.wrapStyleWord = true
        area.accessibleContext.accessibleName = variable.label
        area.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (!updatingValues) onChanged(variable.key, area.text)
            }
        })
        val scroll = JBScrollPane(area)
        controls[variable.key] = area
        return scroll
    }

    private fun enumField(variable: PromptVariable): ComboBox<EnumChoice> {
        val choices = enumChoices(variable)
        val combo = ComboBox(choices.toTypedArray())
        combo.accessibleContext.accessibleName = variable.label
        val selected = currentValue(variable)
        val selectedChoice = choices.firstOrNull { it.id == selected } ?: choices.first()
        combo.selectedItem = selectedChoice
        combo.addActionListener {
            if (!updatingValues) onChanged(variable.key, (combo.selectedItem as? EnumChoice)?.id.orEmpty())
        }
        controls[variable.key] = combo
        return combo
    }

    private fun currentValue(variable: PromptVariable): String {
        val value = values[variable.key] ?: variable.initialValue()
        return value
    }
}

internal fun enumChoices(variable: PromptVariable): List<EnumChoice> =
    variable.options.map { option -> EnumChoice(option.id, option.label) }

internal data class EnumChoice(val id: String, val label: String) {
    override fun toString(): String = label
}
