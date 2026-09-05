package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

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
    private val initialTags = initialDraft.tags
    private val initialTagText = initialTags.joinToString(", ")
    private val tagsField = JBTextField(initialTagText)
    private val markdownEditor = EditorTextField(
        initialDraft.markdown,
        project,
        FileTypeManager.getInstance().getFileTypeByExtension("md"),
    )
    private val wordWrapToggle = JBCheckBox("Word wrap", true)
    private val variableModel = DefaultListModel<PromptVariable>()
    private val variableList = JBList(variableModel)
    private val inspector = VariableInspectorPanel(
        onChanged = { variable -> updateSelected { variable } },
        onTypeChanged = { type -> changeSelectedType(type); loadInspector() },
        onRename = ::renameSelectedVariable,
    )
    private val moveUp = JButton("Move Up").apply { addActionListener { moveVariable(-1) } }
    private val moveDown = JButton("Move Down").apply { addActionListener { moveVariable(1) } }
    private val diagnostics = JBTextArea(2, 0).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        foreground = JBColor.RED
    }
    private val diagnosticScroll = JBScrollPane(diagnostics).apply {
        border = JBUI.Borders.empty()
        isVisible = false
    }
    private var unusedKeys = emptySet<String>()
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
            "Use Move Up or Move Down to change form order. Remove unused variables with Delete or the trash icon."
        variableList.cellRenderer = VariableRenderer(
            isUnused = { variable -> variable.key in unusedKeys },
            isRowHovered = variableListActions::isRowHovered,
            isDeleteHovered = variableListActions::isDeleteHovered,
        )
        variableList.addListSelectionListener { if (!it.valueIsAdjusting) loadInspector() }

        add(createHeader(), BorderLayout.NORTH)
        add(createEditorAndVariables(), BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)

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
        editorPanel.minimumSize = JBUI.size(0, 100)

        val variablePanel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
        variablePanel.border = JBUI.Borders.empty(8, 0, 0, 0)
        val variableListScroll = JBScrollPane(variableList).apply {
            minimumSize = JBUI.size(0, 64)
        }

        val inspectorScroll = JBScrollPane(inspector).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            minimumSize = JBUI.size(0, 100)
            accessibleContext.accessibleName = "Variable inspector"
        }
        val navigation = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            add(variableListScroll, BorderLayout.CENTER)
            add(ResponsiveActionsPanel().apply { add(moveUp); add(moveDown) }, BorderLayout.SOUTH)
        }

        val variableSplitter = OnePixelSplitter(false, 0.34f)
        variableSplitter.firstComponent = navigation
        variableSplitter.secondComponent = inspectorScroll
        variableSplitter.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                val stacked = variableSplitter.width < JBUI.scale(560)
                if (variableSplitter.orientation != stacked) {
                    variableSplitter.orientation = stacked
                    variableSplitter.proportion = if (stacked) 0.28f else 0.34f
                }
            }
        })
        variablePanel.add(variableSplitter, BorderLayout.CENTER)

        val splitter = OnePixelSplitter(true, 0.56f)
        splitter.firstComponent = editorPanel
        splitter.secondComponent = variablePanel
        return splitter
    }

    private fun createFooter(): JComponent {
        val buttons = ResponsiveActionsPanel(FlowLayout.RIGHT)
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
            add(diagnosticScroll, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
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
        showDiagnostic((parseErrors + result.unknownContextKeys.map { "Unknown context: $it" }).firstOrNull().orEmpty())
        revalidate()
        repaint()
    }

    private fun loadInspector() {
        inspector.showVariable(variableList.selectedValue)
        moveUp.isEnabled = variableList.selectedIndex > 0
        moveDown.isEnabled = variableList.selectedIndex in 0 until variableModel.size - 1
    }

    private fun moveVariable(offset: Int) {
        val index = variableList.selectedIndex
        val destination = index + offset
        if (index !in variableState.variables.indices || destination !in variableState.variables.indices) return
        variableState.move(index, offset)
        variableModel.clear()
        variableState.variables.forEach(variableModel::addElement)
        variableList.selectedIndex = destination
        variableList.ensureIndexIsVisible(destination)
        variableList.requestFocusInWindow()
    }

    private fun updateSelected(transform: (PromptVariable) -> PromptVariable) {
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
        val newKey = inspector.keyText.trim()
        val oldKey = variables[index].key
        when {
            !USER_VARIABLE_KEY_REGEX.matches(newKey) -> showDiagnostic("Invalid variable key '$newKey'.")
            variables.any { it.key == newKey && it.key != oldKey } -> showDiagnostic("Variable '$newKey' already exists.")
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
            showDiagnostic(error)
            return
        }
        onSave(currentDraft())
    }

    private fun showDiagnostic(message: String) {
        diagnostics.text = message
        diagnostics.accessibleContext.accessibleName = message
        diagnostics.caretPosition = 0
        diagnosticScroll.isVisible = message.isNotEmpty()
        revalidate()
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
        tags = if (tagsField.text == initialTagText) initialTags
        else tagsField.text.split(',').map(String::trim).filter(String::isNotEmpty),
        variables = variableState.variables,
        markdown = markdownEditor.text,
    )

    private fun editSnapshot() = AuthorEditSnapshot.capture(
        currentDraft(), tagsField.text, variableList.selectedValue, inspector.keyText, inspector.enumText,
    )

    override fun dispose() = Unit

}
