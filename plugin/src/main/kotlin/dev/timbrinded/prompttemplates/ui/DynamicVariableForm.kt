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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal class DynamicVariableForm(
    variables: List<PromptVariable>,
    private val accents: Map<String, VariableAccent>,
    private val values: Map<String, String>,
    private val onChanged: (String, String) -> Unit,
) : JPanel() {
    private val controls = mutableMapOf<String, JComponent>()

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
        panel.add(label, BorderLayout.NORTH)

        val control: JComponent = when (variable.type) {
            PromptVariableType.TEXT -> textField(variable)
            PromptVariableType.MULTILINE -> multilineField(variable)
            PromptVariableType.ENUM -> enumField(variable)
        }
        label.labelFor = control
        controls[variable.key] = control
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
                onChanged(variable.key, field.text)
            }
        })
        return field
    }

    private fun multilineField(variable: PromptVariable): JBScrollPane {
        val area = JBTextArea(currentValue(variable))
        area.rows = variable.minimumRows ?: 4
        area.lineWrap = true
        area.wrapStyleWord = true
        area.accessibleContext.accessibleName = variable.label
        area.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                onChanged(variable.key, area.text)
            }
        })
        val scroll = JBScrollPane(area)
        scroll.preferredSize = Dimension(JBUI.scale(320), JBUI.scale(86))
        scroll.accessibleContext.accessibleName = variable.label
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
            onChanged(variable.key, (combo.selectedItem as? EnumChoice)?.id.orEmpty())
        }
        return combo
    }

    private fun currentValue(variable: PromptVariable): String {
        val value = values[variable.key] ?: variable.defaultValue.orEmpty()
        return value
    }
}

internal fun enumChoices(variable: PromptVariable): List<EnumChoice> =
    variable.options.map { option -> EnumChoice(option.id, option.label) }

internal data class EnumChoice(val id: String, val label: String) {
    override fun toString(): String = label
}
