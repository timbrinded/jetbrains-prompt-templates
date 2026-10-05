package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.referencedUserVariables
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * The Use view of one template: its inputs, resolved context, rendered preview and actions. It is built for one
 * template and refreshed in place with [update] as the shared invocation changes.
 */
internal class UseDetailView(
    project: Project,
    detail: PromptDetailState.Use,
    private val onAction: (UseViewAction) -> Unit,
    onValueChanged: (String, String) -> Unit,
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))), Disposable {
    private val dynamicForm: DynamicVariableForm
    private val previewField: EditorTextField
    private val highlights: RenderedVariableHighlightController
    private val contextArea = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Resolved context"
    }
    private val validationLabel = JBLabel().apply { foreground = com.intellij.ui.JBColor.RED }
    private val actionButtons = mutableMapOf<UseViewAction, JButton>()

    init {
        val stored = detail.stored
        border = JBUI.Borders.empty(10)
        val title = JBLabel(stored.template.metadata.name).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        val titleRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(createFileActionsMenu(), BorderLayout.EAST)
        }
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(titleRow, BorderLayout.NORTH)
            add(JBLabel(stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE).toString()), BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)

        val variableAccents = VariableAccentPalette.forVariables(stored.template.metadata.variables)
        val inputVariables = referencedUserVariables(stored.template)
        dynamicForm = DynamicVariableForm(
            inputVariables,
            variableAccents,
            detail.values,
            onValueChanged,
        )
        val formPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(dynamicForm).apply {
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
        previewField = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setViewer(true)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(190))
            accessibleContext.accessibleName = "Rendered prompt preview"
            addSettingsProvider { editor ->
                editor.settings.isUseSoftWraps = true
                configurePromptEditorScrollbars(editor.scrollPane)
            }
        }
        highlights = RenderedVariableHighlightController(previewField, variableAccents)
        val previewPanel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))).apply {
            add(contextArea, BorderLayout.NORTH)
            add(previewField, BorderLayout.CENTER)
            add(validationLabel, BorderLayout.SOUTH)
        }
        add(createUseViewContent(inputVariables.isNotEmpty(), formPanel, previewPanel), BorderLayout.CENTER)
        add(createUseActions(), BorderLayout.SOUTH)
    }

    fun update(detail: PromptDetailState.Use) {
        dynamicForm.updateValues(detail.values)
        if (previewField.text != detail.render.renderedText) previewField.text = detail.render.renderedText
        actionButtons[UseViewAction.INSERT]?.text = detail.session.insertionLabel
        actionButtons[UseViewAction.COPY_PROMPT]?.isEnabled = !detail.session.capturing
        actionButtons[UseViewAction.INSERT]?.isEnabled = !detail.session.capturing
        highlights.update(detail.render)
        validationLabel.text = detail.session.deliveryProblem.orEmpty()
        contextArea.text = if (detail.referencedContext.isEmpty()) {
            ""
        } else {
            detail.referencedContext.joinToString("\n", prefix = "Context\n") { key ->
                val context = detail.context[key]
                if (context?.value != null) {
                    "✓ $key — ${context.displaySummary.orEmpty()}"
                } else {
                    "! $key — ${context?.errorMessage ?: "unknown"}"
                }
            }
        }
        if (detail.session.contextChanged) {
            contextArea.append("\nContext changed. Refresh Context to capture it; this preview is unchanged.")
        }
    }

    fun focusVariable(key: String) = dynamicForm.focusVariable(key)

    private fun createUseActions(): JComponent {
        val primary = ResponsiveActionsPanel()
        USE_VIEW_PRIMARY_ACTIONS.forEach { action ->
            primary.add(JButton(action.label).apply {
                if (action == UseViewAction.COPY_PROMPT) font = JBUI.Fonts.label().asBold()
                actionButtons[action] = this
                addActionListener { onAction(action) }
            })
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(primary, BorderLayout.CENTER)
        }
    }

    private fun createFileActionsMenu(): JComponent {
        val popup = JPopupMenu()
        USE_VIEW_FILE_ACTIONS.forEach { action ->
            if (action == UseViewAction.EXPORT_TEMPLATE || action == UseViewAction.DELETE) popup.addSeparator()
            popup.add(JMenuItem(action.label).apply {
                addActionListener { onAction(action) }
            })
        }
        return JButton("File ▾").apply {
            accessibleContext.accessibleName = "Template file actions"
            addActionListener { popup.show(this, 0, height) }
        }
    }

    override fun dispose() = highlights.dispose()
}

internal enum class UseViewAction(val label: String) {
    COPY_PROMPT("Copy Prompt"),
    INSERT("Insert…"),
    EDIT("Edit"),
    DUPLICATE("Duplicate Template…"),
    OPEN_MARKDOWN("Open Markdown"),
    REVEAL("Reveal in File Manager"),
    COPY_PATH("Copy Markdown Path"),
    EXPORT_TEMPLATE("Export Template Markdown…"),
    EXPORT_RENDERED("Export Rendered Markdown…"),
    OPEN_RENDERED_SCRATCH("Open Rendered Prompt as Scratch Markdown"),
    DELETE("Delete"),
    ADD_CONTEXT("Add Context…"),
    REFRESH_CONTEXT("Refresh Context"),
    RELOAD_TEMPLATE("Reload Template"),
    SELECT_INSERTION_TARGET("Use Active Editor as Insertion Target"),
    RESET_VALUES("Reset Values to Defaults"),
}

internal val USE_VIEW_PRIMARY_ACTIONS = listOf(
    UseViewAction.COPY_PROMPT,
    UseViewAction.INSERT,
    UseViewAction.EDIT,
)

internal val USE_VIEW_FILE_ACTIONS = listOf(
    UseViewAction.DUPLICATE,
    UseViewAction.ADD_CONTEXT,
    UseViewAction.REFRESH_CONTEXT,
    UseViewAction.RELOAD_TEMPLATE,
    UseViewAction.SELECT_INSERTION_TARGET,
    UseViewAction.RESET_VALUES,
    UseViewAction.OPEN_MARKDOWN,
    UseViewAction.REVEAL,
    UseViewAction.COPY_PATH,
    UseViewAction.EXPORT_TEMPLATE,
    UseViewAction.EXPORT_RENDERED,
    UseViewAction.OPEN_RENDERED_SCRATCH,
    UseViewAction.DELETE,
)
