package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultFormatter

/** Edits one variable; the author owns schema order, Markdown, and draft lifetime. */
internal class VariableInspectorPanel(
    private val onChanged: (PromptVariable) -> Unit,
    private val onTypeChanged: (PromptVariableType) -> Unit,
    onRename: () -> Unit,
) : JPanel(BorderLayout()) {
    private val key = JBTextField().apply { accessibleContext.accessibleName = "Variable key" }
    private val label = JBTextField()
    private val type = ComboBox(PromptVariableType.entries.toTypedArray())
    private val required = JBCheckBox("Required")
    private val description = JBTextField()
    private val options = JBTextField()
    private val optionsLabel = JBLabel("Enum choices (; separated):")
    private val enumDefault = ComboBox<EnumChoice>()
    private val enumDefaultLabel = JBLabel("Default option:")
    private val hasDefault = JBCheckBox("Use authored default")
    private val textDefault = JBTextField()
    private val textDefaultLabel = JBLabel("Default value:")
    private val multilineDefault = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Default value:"
        setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, textDefault.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS))
        setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, textDefault.getFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS))
    }
    private val multilineDefaultScroll = JBScrollPane(multilineDefault)
    private val multilineDefaultLabel = JBLabel("Default value:")
    private val placeholder = JBTextField()
    private val placeholderLabel = JBLabel("Input placeholder:")
    private val rows = JSpinner(SpinnerNumberModel(4, 1, null, 1)).apply {
        accessibleContext.accessibleName = "Minimum rows:"
    }
    private val defaultRows = JButton("Use Default (4)")
    private val rowsLabel = JBLabel("Minimum rows:")
    private val rowsPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
        add(rows, BorderLayout.CENTER)
        add(defaultRows, BorderLayout.EAST)
    }
    private var selected: PromptVariable? = null
    private var loading = false
    private var optionIdentities = emptyList<EnumOption>()
    private var preferredDefaultId: String? = null

    val keyText: String get() = key.text
    val enumText: String get() = options.text

    init {
        val rename = JButton("Rename").apply { addActionListener { onRename() } }
        val keyRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(key, BorderLayout.CENTER)
            add(rename, BorderLayout.EAST)
        }
        options.toolTipText = "Separate choices with semicolons. The selected choice is inserted unchanged."
        hasDefault.toolTipText = "Checked with an empty value stores an explicit empty default. Unchecked stores no default."
        listOf(optionsLabel to options, enumDefaultLabel to enumDefault, textDefaultLabel to textDefault,
            multilineDefaultLabel to multilineDefault, placeholderLabel to placeholder, rowsLabel to rows)
            .forEach { (label, field) -> label.labelFor = field }
        add(FormBuilder.createFormBuilder().setVertical(true)
            .addLabeledComponent("Key:", keyRow)
            .addLabeledComponent("Label:", label)
            .addLabeledComponent("Type:", type)
            .addComponent(required)
            .addLabeledComponent("Description:", description)
            .addLabeledComponent(optionsLabel, options)
            .addLabeledComponent(enumDefaultLabel, enumDefault)
            .addComponent(hasDefault)
            .addLabeledComponent(textDefaultLabel, textDefault)
            .addLabeledComponent(multilineDefaultLabel, multilineDefaultScroll)
            .addLabeledComponent(placeholderLabel, placeholder)
            .addLabeledComponent(rowsLabel, rowsPanel)
            .panel, BorderLayout.NORTH)
        // Commit each valid numeric edit immediately, so raw spinner text cannot escape the draft guard.
        val rowEditor = rows.editor as JSpinner.DefaultEditor
        (rowEditor.textField.formatter as DefaultFormatter).apply {
            allowsInvalid = false
            commitsOnValidEdit = true
        }
        listOf(key, rename, label, type, required, description, options, enumDefault, hasDefault,
            textDefault, multilineDefault, placeholder, rowEditor.textField, defaultRows).forEach { field ->
            field.addFocusListener(object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    scrollRectToVisible(SwingUtilities.convertRectangle(field, Rectangle(field.size), this@VariableInspectorPanel))
                }
            })
        }
        label.document.addDocumentListener(listener { update { it.copy(label = label.text) } })
        description.document.addDocumentListener(listener { update { it.copy(description = description.text.ifBlank { null }) } })
        placeholder.document.addDocumentListener(listener { update { it.copy(placeholder = placeholder.text.ifEmpty { null }) } })
        textDefault.document.addDocumentListener(listener { if (hasDefault.isSelected) update { it.copy(defaultValue = textDefault.text) } })
        multilineDefault.document.addDocumentListener(listener { if (hasDefault.isSelected) update { it.copy(defaultValue = multilineDefault.text) } })
        hasDefault.addActionListener {
            if (!loading) {
                update { it.copy(defaultValue = if (!hasDefault.isSelected) null else
                    if (it.type == PromptVariableType.MULTILINE) multilineDefault.text else textDefault.text) }
                enableDefaultInput()
            }
        }
        type.addActionListener { if (!loading) (type.selectedItem as? PromptVariableType)?.let(onTypeChanged) }
        required.addActionListener { update { it.copy(required = required.isSelected) } }
        rows.addChangeListener { update { it.copy(minimumRows = rows.value as Int) } }
        defaultRows.addActionListener {
            update { it.copy(minimumRows = null) }
            loading = true
            try { rows.value = 4 } finally { loading = false }
        }
        options.document.addDocumentListener(listener {
            val choices = parseEnumOptionInput(options.text, optionIdentities)
            optionIdentities = (optionIdentities + choices).distinctBy { it.label }
            update { it.copy(required = true, options = choices,
                defaultValue = preferredDefaultId?.takeIf { id -> choices.any { it.id == id } } ?: choices.firstOrNull()?.id) }
            loadEnumDefault()
        })
        enumDefault.addActionListener {
            if (!loading) {
                preferredDefaultId = (enumDefault.selectedItem as? EnumChoice)?.id
                update { it.copy(defaultValue = preferredDefaultId) }
            }
        }
    }

    fun showVariable(variable: PromptVariable?) {
        selected = variable
        optionIdentities = variable?.options.orEmpty()
        preferredDefaultId = variable?.defaultValue
        loading = true
        try {
            key.text = variable?.key.orEmpty()
            label.text = variable?.label.orEmpty()
            type.selectedItem = variable?.type ?: PromptVariableType.TEXT
            required.isSelected = variable?.required ?: true
            description.text = variable?.description.orEmpty()
            options.text = variable?.options?.joinToString("; ") { it.label }.orEmpty()
            hasDefault.isSelected = variable?.defaultValue != null
            textDefault.text = variable?.defaultValue.orEmpty()
            multilineDefault.text = variable?.defaultValue.orEmpty()
            placeholder.text = variable?.placeholder.orEmpty()
            rows.value = variable?.minimumRows ?: 4
            listOf(key, label, type, required, description, options, enumDefault, hasDefault, placeholder, rows, defaultRows)
                .forEach { it.isEnabled = variable != null }
            val presentation = variableTypePresentation(variable?.type)
            required.isVisible = presentation.requiredVisible
            listOf(optionsLabel, options, enumDefaultLabel, enumDefault).forEach { it.isVisible = presentation.enumChoicesVisible }
            hasDefault.isVisible = presentation.requiredVisible
            listOf(textDefaultLabel, textDefault).forEach { it.isVisible = variable?.type == PromptVariableType.TEXT }
            listOf(multilineDefaultLabel, multilineDefaultScroll, rowsLabel, rowsPanel)
                .forEach { it.isVisible = variable?.type == PromptVariableType.MULTILINE }
            listOf(placeholderLabel, placeholder).forEach { it.isVisible = presentation.requiredVisible }
            enableDefaultInput()
        } finally { loading = false }
        loadEnumDefault()
        revalidate()
        repaint()
    }

    private fun loadEnumDefault() {
        loading = true
        try {
            val choices = selected?.let(::enumChoices).orEmpty()
            enumDefault.model = DefaultComboBoxModel(choices.toTypedArray())
            enumDefault.selectedItem = choices.firstOrNull { it.id == selected?.defaultValue }
        } finally { loading = false }
    }

    private fun enableDefaultInput() {
        textDefault.isEnabled = selected != null && hasDefault.isSelected
        multilineDefault.isEnabled = textDefault.isEnabled
    }

    private fun update(transform: (PromptVariable) -> PromptVariable) {
        if (loading) return
        selected = selected?.let(transform)?.also(onChanged)
    }

    private fun listener(block: () -> Unit) = object : DocumentAdapter() {
        override fun textChanged(event: DocumentEvent) { if (!loading) block() }
    }
}
