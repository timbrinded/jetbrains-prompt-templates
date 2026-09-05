package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.LinearPlaceholderParser
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateReconciler
import dev.timbrinded.prompttemplates.core.USER_VARIABLE_KEY_REGEX
import dev.timbrinded.prompttemplates.core.VariableEditorState
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
    private val variableState = VariableEditorState(initialDraft.variables, reconciler)
    private val draftId = initialDraft.id
    private val nameField = JBTextField(initialDraft.name)
    private val descriptionField = JBTextField(initialDraft.description.orEmpty())
    private val tagsField = JBTextField(initialDraft.tags.joinToString(", "))
    private val markdownEditor = EditorTextField(
        initialDraft.markdown,
        project,
        FileTypeManager.getInstance().getFileTypeByExtension("md"),
    )
    private val wordWrapToggle = JBCheckBox("Word wrap", true)
    private val variableModel = DefaultListModel<PromptVariable>()
    private val variableList = JBList(variableModel)
    private val keyField = JBTextField()
    private val labelField = JBTextField()
    private val typeField = ComboBox(PromptVariableType.entries.toTypedArray())
    private val requiredField = JBCheckBox("Required")
    private val descriptionVariableField = JBTextField()
    private val optionsLabel = JBLabel("Enum choices (; separated):")
    private val optionsField = JBTextField()
    private val diagnostics = JBLabel()
    private var unusedKeys = emptySet<String>()
    private var updatingInspector = false
    private val initialSnapshot: AuthorEditSnapshot
    private val variableListActions = UnusedVariableListActions(
        list = variableList,
        isUnused = { variable -> variable.key in unusedKeys },
        onRemove = ::removeUnusedVariable,
    )

    init {
        border = JBUI.Borders.empty(8)
        markdownEditor.setOneLineMode(false)
        markdownEditor.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(240))
        markdownEditor.accessibleContext.accessibleName = "Template Markdown"
        markdownEditor.addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = wordWrapToggle.isSelected
            configurePromptEditorScrollbars(editor.scrollPane)
        }
        wordWrapToggle.addActionListener {
            markdownEditor.editor?.settings?.isUseSoftWraps = wordWrapToggle.isSelected
        }

        variableList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        variableList.accessibleContext.accessibleName = "Template variables"
        variableList.accessibleContext.accessibleDescription =
            "Unused variables can be removed with Delete or the trash icon."
        variableList.cellRenderer = VariableRenderer(
            isUnused = { variable -> variable.key in unusedKeys },
            isRowHovered = variableListActions::isRowHovered,
            isDeleteHovered = variableListActions::isDeleteHovered,
        )
        variableList.addListSelectionListener { if (!it.valueIsAdjusting) loadInspector() }

        add(createHeader(), BorderLayout.NORTH)
        add(createEditorAndVariables(), BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)

        installInspectorListeners()
        reconcileVariables()
        PlaceholderHighlightController(markdownEditor, this, ::reconcileVariables)
        initialSnapshot = editSnapshot()
    }

    private fun createHeader(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("Description:", descriptionField)
        .addLabeledComponent("Tags:", tagsField)
        .panel

    private fun createEditorAndVariables(): JComponent {
        val editorPanel = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4)))
        editorPanel.border = JBUI.Borders.emptyTop(8)
        val editorHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Template Markdown"), BorderLayout.WEST)
            add(wordWrapToggle, BorderLayout.EAST)
        }
        editorPanel.add(editorHeader, BorderLayout.NORTH)
        editorPanel.add(markdownEditor, BorderLayout.CENTER)

        val variablePanel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
        variablePanel.border = JBUI.Borders.empty(8, 0, 0, 0)
        val variableListScroll = JBScrollPane(variableList).apply {
            minimumSize = Dimension(JBUI.scale(140), JBUI.scale(210))
        }

        optionsField.toolTipText = "Separate choices with semicolons. The selected choice is inserted unchanged."
        optionsLabel.labelFor = optionsField
        val renameButton = JButton("Rename")
        renameButton.addActionListener { renameSelectedVariable() }
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
            .addLabeledComponent(optionsLabel, optionsField)
            .panel
        inspector.minimumSize = Dimension(JBUI.scale(280), JBUI.scale(210))

        val variableSplitter = OnePixelSplitter(false, 0.34f)
        variableSplitter.firstComponent = variableListScroll
        variableSplitter.secondComponent = inspector
        variablePanel.add(variableSplitter, BorderLayout.CENTER)

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
        optionsField.document.addDocumentListener(textListener {
            updateSelected { variable ->
                val options = parseEnumOptionInput(optionsField.text)
                variable.copy(
                    required = true,
                    options = options,
                    defaultValue = variable.defaultValue?.takeIf { default -> options.any { it.id == default } }
                        ?: options.firstOrNull()?.id,
                )
            }
        })
        typeField.addActionListener {
            if (!updatingInspector) {
                val selectedType = typeField.selectedItem as? PromptVariableType ?: return@addActionListener
                changeSelectedType(selectedType)
                loadInspector()
            }
        }
        requiredField.addActionListener {
            if (!updatingInspector) updateSelected { it.copy(required = requiredField.isSelected) }
        }
    }

    private fun reconcileVariables() {
        val result = variableState.reconcile(markdownEditor.text)
        val variables = variableState.variables
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
            optionsField.text = variable?.options?.joinToString("; ") { it.label }.orEmpty()
            val enabled = variable != null
            listOf(keyField, labelField, typeField, requiredField, descriptionVariableField, optionsField)
                .forEach { it.isEnabled = enabled }
            val presentation = variableTypePresentation(variable?.type)
            requiredField.isVisible = presentation.requiredVisible
            optionsLabel.isVisible = presentation.enumChoicesVisible
            optionsField.isVisible = presentation.enumChoicesVisible
        } finally {
            updatingInspector = false
        }
        revalidate()
        repaint()
    }

    private fun updateSelected(transform: (PromptVariable) -> PromptVariable) {
        if (updatingInspector) return
        val index = variableList.selectedIndex
        if (index !in variableState.variables.indices) return
        variableState.updateAt(index, transform)
        variableModel.set(index, variableState.variables[index])
        variableList.selectedIndex = index
    }

    private fun changeSelectedType(type: PromptVariableType) {
        val index = variableList.selectedIndex
        if (index !in variableState.variables.indices) return
        variableState.changeTypeAt(index, type)
        variableModel.set(index, variableState.variables[index])
        variableList.selectedIndex = index
    }

    private fun removeUnusedVariable(variable: PromptVariable) {
        if (variable.key !in unusedKeys) return
        variableState.remove(setOf(variable.key))
        reconcileVariables()
    }

    private fun renameSelectedVariable() {
        val index = variableList.selectedIndex
        val variables = variableState.variables
        if (index !in variables.indices) return
        val newKey = keyField.text.trim()
        val oldKey = variables[index].key
        when {
            !USER_VARIABLE_KEY_REGEX.matches(newKey) -> diagnostics.text = "Invalid variable key '$newKey'."
            variables.any { it.key == newKey && it.key != oldKey } -> diagnostics.text = "Variable '$newKey' already exists."
            else -> {
                variableState.updateAt(index) { it.copy(key = newKey) }
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
        onSave(currentDraft())
    }

    internal fun confirmDiscardChanges(): Boolean {
        if (editSnapshot() == initialSnapshot) return true
        val discard = Messages.showDialog(
            project,
            "Discard the unsaved changes to this template?",
            "Unsaved Template",
            arrayOf("Discard", "Keep Editing"),
            1,
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!discard) nameField.requestFocusInWindow()
        return discard
    }

    private fun currentDraft() = PromptTemplateDraft(
        id = draftId,
        name = nameField.text,
        description = descriptionField.text,
        tags = tagsField.text.split(',').map(String::trim).filter(String::isNotEmpty),
        variables = variableState.variables,
        markdown = markdownEditor.text,
    )

    private fun editSnapshot() = AuthorEditSnapshot.capture(
        currentDraft(), tagsField.text, variableList.selectedValue, keyField.text, optionsField.text,
    )

    private fun textListener(block: () -> Unit): DocumentAdapter = object : DocumentAdapter() {
        override fun textChanged(event: DocumentEvent) {
            if (!updatingInspector) block()
        }
    }

    override fun dispose() = Unit

}
