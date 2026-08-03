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
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class DynamicVariableForm(
    variables: List<PromptVariable>,
    private val values: MutableMap<String, String>,
    private val onChanged: () -> Unit,
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
        val panel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(4)))
        panel.isOpaque = false
        val suffix = if (variable.required) " *" else ""
        val label = JBLabel(variable.label + suffix)
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
            val help = JBLabel(description)
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
                values[variable.key] = field.text
                onChanged()
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
                values[variable.key] = area.text
                onChanged()
            }
        })
        val scroll = JBScrollPane(area)
        scroll.preferredSize = Dimension(JBUI.scale(320), JBUI.scale(86))
        scroll.accessibleContext.accessibleName = variable.label
        return scroll
    }

    private fun enumField(variable: PromptVariable): ComboBox<EnumChoice> {
        val choices = buildList {
            add(EnumChoice("", "Select…"))
            variable.options.forEach { add(EnumChoice(it.id, it.label)) }
        }
        val combo = ComboBox(choices.toTypedArray())
        combo.accessibleContext.accessibleName = variable.label
        val selected = currentValue(variable)
        combo.selectedItem = choices.firstOrNull { it.id == selected } ?: choices.first()
        combo.addActionListener {
            values[variable.key] = (combo.selectedItem as? EnumChoice)?.id.orEmpty()
            onChanged()
        }
        return combo
    }

    private fun currentValue(variable: PromptVariable): String {
        val value = values[variable.key] ?: variable.defaultValue.orEmpty()
        values.putIfAbsent(variable.key, value)
        return value
    }

    private data class EnumChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }
}
