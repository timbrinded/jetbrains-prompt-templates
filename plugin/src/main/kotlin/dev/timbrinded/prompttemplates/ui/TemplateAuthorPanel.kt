package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.LinearPlaceholderParser
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateReconciler
import dev.timbrinded.prompttemplates.core.USER_VARIABLE_KEY_REGEX
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class TemplateAuthorPanel(
    private val project: Project,
    initialDraft: PromptTemplateDraft,
    private val onSave: (PromptTemplateDraft) -> Unit,
    private val onCancel: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val parser = LinearPlaceholderParser()
    private val reconciler = TemplateReconciler()
    private val draftId = initialDraft.id
    private val nameField = JBTextField(initialDraft.name)
    private val descriptionField = JBTextField(initialDraft.description.orEmpty())
    private val tagsField = JBTextField(initialDraft.tags.joinToString(", "))
    private val markdownEditor = EditorTextField(
        initialDraft.markdown,
        project,
        FileTypeManager.getInstance().getFileTypeByExtension("md"),
    )
    private val variableModel = DefaultListModel<PromptVariable>()
    private val variableList = JBList(variableModel)
    private val keyField = JBTextField()
    private val labelField = JBTextField()
    private val typeField = ComboBox(PromptVariableType.entries.toTypedArray())
    private val requiredField = JBCheckBox("Required")
    private val descriptionVariableField = JBTextField()
    private val optionsArea = JBTextArea()
    private val optionsScroll = JBScrollPane(optionsArea)
    private val diagnostics = JBLabel()
    private var variables = initialDraft.variables.toMutableList()
    private var unusedKeys = emptySet<String>()
    private var updatingInspector = false

    init {
        border = JBUI.Borders.empty(8)
        markdownEditor.setOneLineMode(false)
        markdownEditor.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(240))
        markdownEditor.accessibleContext.accessibleName = "Template Markdown"

        add(createHeader(), BorderLayout.NORTH)
        add(createEditorAndVariables(), BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)

        variableList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        variableList.cellRenderer = VariableRenderer { variable -> variable.key in unusedKeys }
        variableList.addListSelectionListener { if (!it.valueIsAdjusting) loadInspector() }
        installInspectorListeners()
        reconcileVariables()
        PlaceholderHighlightController(markdownEditor, this, ::reconcileVariables)
    }

    private fun createHeader(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("Description:", descriptionField)
        .addLabeledComponent("Tags:", tagsField)
        .panel

    private fun createEditorAndVariables(): JComponent {
        val editorPanel = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4)))
        editorPanel.border = JBUI.Borders.emptyTop(8)
        editorPanel.add(JBLabel("Template Markdown"), BorderLayout.NORTH)
        editorPanel.add(markdownEditor, BorderLayout.CENTER)

        val variablePanel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
        variablePanel.border = JBUI.Borders.empty(8, 0, 0, 0)
        variablePanel.add(JBScrollPane(variableList), BorderLayout.WEST)
        variableList.preferredSize = Dimension(JBUI.scale(190), JBUI.scale(210))

        optionsArea.rows = 5
        optionsArea.lineWrap = true
        optionsArea.toolTipText = "One option per line: Label => rendered value"
        optionsScroll.preferredSize = Dimension(JBUI.scale(300), JBUI.scale(90))
        val renameButton = JButton("Rename")
        renameButton.addActionListener { renameSelectedVariable() }
        val removeUnused = JButton("Remove Unused")
        removeUnused.addActionListener {
            variables.removeAll { it.key in unusedKeys }
            reconcileVariables()
        }
        val keyRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(keyField, BorderLayout.CENTER)
            add(renameButton, BorderLayout.EAST)
        }
        val inspector = FormBuilder.createFormBuilder()
            .addLabeledComponent("Key:", keyRow)
            .addLabeledComponent("Label:", labelField)
            .addLabeledComponent("Type:", typeField)
            .addComponent(requiredField)
            .addLabeledComponent("Description:", descriptionVariableField)
            .addLabeledComponent("Enum options:", optionsScroll)
            .addComponent(removeUnused)
            .panel
        variablePanel.add(inspector, BorderLayout.CENTER)

        val splitter = OnePixelSplitter(true, 0.56f)
        splitter.firstComponent = editorPanel
        splitter.secondComponent = variablePanel
        return splitter
    }

    private fun createFooter(): JComponent {
        diagnostics.foreground = JBColor.RED
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
        JButton("Cancel").also { button ->
            button.addActionListener { onCancel() }
            buttons.add(button)
        }
        JButton("Save Template").also { button ->
            button.addActionListener { save() }
            buttons.add(button)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(diagnostics, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
    }

    private fun installInspectorListeners() {
        labelField.document.addDocumentListener(textListener { updateSelected { it.copy(label = labelField.text) } })
        descriptionVariableField.document.addDocumentListener(
            textListener { updateSelected { it.copy(description = descriptionVariableField.text.ifBlank { null }) } },
        )
        optionsArea.document.addDocumentListener(textListener {
            updateSelected { variable ->
                val options = parseOptions(optionsArea.text)
                variable.copy(
                    options = options,
                    defaultValue = variable.defaultValue?.takeIf { default -> options.any { it.id == default } }
                        ?: options.firstOrNull()?.id,
                )
            }
        })
        typeField.addActionListener {
            if (!updatingInspector) {
                updateSelected { variable ->
                    val type = typeField.selectedItem as PromptVariableType
                    val options = if (type == PromptVariableType.ENUM && variable.options.isEmpty()) {
                        listOf(EnumOption("option", "Option", "Option"))
                    } else {
                        variable.options
                    }
                    variable.copy(
                        type = type,
                        options = options,
                        defaultValue = if (type == PromptVariableType.ENUM) {
                            variable.defaultValue ?: options.firstOrNull()?.id
                        } else {
                            variable.defaultValue
                        },
                    )
                }
                loadInspector()
            }
        }
        requiredField.addActionListener {
            if (!updatingInspector) updateSelected { it.copy(required = requiredField.isSelected) }
        }
    }

    private fun reconcileVariables() {
        val result = reconciler.reconcile(markdownEditor.text, variables)
        variables = result.variables.toMutableList()
        unusedKeys = result.unusedKeys
        val selectedKey = variableList.selectedValue?.key
        variableModel.clear()
        variables.forEach(variableModel::addElement)
        val selectedIndex = variables.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
        if (variables.isNotEmpty()) variableList.selectedIndex = selectedIndex

        val parseErrors = parser.parse(markdownEditor.text).diagnostics.map { it.message }
        diagnostics.text = (parseErrors + result.unknownContextKeys.map { "Unknown context: $it" }).firstOrNull().orEmpty()
        revalidate()
        repaint()
    }

    private fun loadInspector() {
        val variable = variableList.selectedValue
        updatingInspector = true
        try {
            keyField.text = variable?.key.orEmpty()
            labelField.text = variable?.label.orEmpty()
            typeField.selectedItem = variable?.type ?: PromptVariableType.TEXT
            requiredField.isSelected = variable?.required ?: true
            descriptionVariableField.text = variable?.description.orEmpty()
            optionsArea.text = variable?.options?.joinToString("\n") { "${it.label} => ${it.value}" }.orEmpty()
            val enabled = variable != null
            listOf(keyField, labelField, typeField, requiredField, descriptionVariableField, optionsArea)
                .forEach { it.isEnabled = enabled }
            optionsArea.isEnabled = variable != null && variable.type == PromptVariableType.ENUM
        } finally {
            updatingInspector = false
        }
    }

    private fun updateSelected(transform: (PromptVariable) -> PromptVariable) {
        if (updatingInspector) return
        val index = variableList.selectedIndex
        if (index !in variables.indices) return
        val updated = transform(variables[index])
        variables[index] = updated
        variableModel.set(index, updated)
        variableList.selectedIndex = index
    }

    private fun renameSelectedVariable() {
        val index = variableList.selectedIndex
        if (index !in variables.indices) return
        val newKey = keyField.text.trim()
        val oldKey = variables[index].key
        when {
            !USER_VARIABLE_KEY_REGEX.matches(newKey) -> diagnostics.text = "Invalid variable key '$newKey'."
            variables.any { it.key == newKey && it.key != oldKey } -> diagnostics.text = "Variable '$newKey' already exists."
            else -> {
                variables[index] = variables[index].copy(key = newKey)
                WriteCommandAction.runWriteCommandAction(project) {
                    markdownEditor.document.setText(reconciler.rename(markdownEditor.text, oldKey, newKey))
                }
            }
        }
    }

    private fun save() {
        val parseResult = parser.parse(markdownEditor.text)
        val error = when {
            nameField.text.isBlank() -> "Template name is required."
            parseResult.diagnostics.isNotEmpty() -> parseResult.diagnostics.first().message
            else -> null
        }
        if (error != null) {
            diagnostics.text = error
            return
        }
        onSave(
            PromptTemplateDraft(
                id = draftId,
                name = nameField.text,
                description = descriptionField.text,
                tags = tagsField.text.split(',').map(String::trim).filter(String::isNotEmpty),
                variables = variables,
                markdown = markdownEditor.text,
            ),
        )
    }

    private fun parseOptions(raw: String): List<EnumOption> {
        val used = mutableSetOf<String>()
        return raw.lineSequence().map(String::trim).filter(String::isNotEmpty).mapIndexed { index, line ->
            val parts = line.split("=>", limit = 2)
            val label = parts[0].trim()
            val value = parts.getOrElse(1) { label }.trim()
            var id = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "option-${index + 1}" }
            var suffix = 2
            val base = id
            while (!used.add(id)) {
                id = "$base-$suffix"
                suffix++
            }
            EnumOption(id, label, value)
        }.toList()
    }

    private fun textListener(block: () -> Unit): DocumentAdapter = object : DocumentAdapter() {
        override fun textChanged(event: DocumentEvent) {
            if (!updatingInspector) block()
        }
    }

    override fun dispose() = Unit

    private class VariableRenderer(
        private val isUnused: (PromptVariable) -> Boolean,
    ) : com.intellij.ui.ColoredListCellRenderer<PromptVariable>() {
        override fun customizeCellRenderer(
            list: javax.swing.JList<out PromptVariable>,
            value: PromptVariable,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            append(value.key)
            append("  ${value.type.name.lowercase()}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (isUnused(value)) append("  unused", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
