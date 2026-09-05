package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.referencedUserVariables
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesWorkspaceState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.cancel

internal class PromptTemplatesPanel(
    private val project: Project,
) : JPanel(), Disposable, PromptTemplatesView {
    private val coroutineScope = project.service<PromptTemplatesProjectService>().childScope("PromptTemplatesPanel")
    private val settings = PromptTemplatesSettings.getInstance()
    private val workspace = PromptTemplatesWorkspaceState.getInstance(project)
    private val controller by lazy(LazyThreadSafetyMode.NONE) {
        PromptTemplatesController(project, this, settings, workspace, coroutineScope)
    }
    private val searchField = SearchTextField(false)
    private val mutationControls = mutableListOf<JButton>()
    private val libraryDiagnosticLabel = JBLabel().apply {
        foreground = com.intellij.ui.JBColor.RED
        border = JBUI.Borders.empty(2, 6, 6, 6)
        isVisible = false
    }
    private val returnToAuthorButton = JButton("Return to Template Editor").apply {
        isVisible = false
        addActionListener { showNarrowDetail() }
    }
    private val libraryTree = TemplateLibraryTree(
        onSelection = controller::onLibrarySelection,
        onCommand = { command, target -> controller.performLibraryCommand(command, target) },
        onMove = { source, destination, placement -> controller.moveEntry(source, destination, placement) },
        onExpansionChanged = workspace::replaceExpandedFolderPaths,
    )
    private val libraryPanel = createLibraryPanel()
    private val detailCards = JPanel(BorderLayout())
    private val detailWrapper = JPanel(BorderLayout())
    private val backButton = JButton("‹ Library")
    private val outerLayout = CardLayout()
    private val widePanel = JPanel(BorderLayout())
    private val wideSplitter = OnePixelSplitter(false, settings.splitterProportion)
    private val narrowLayout = CardLayout()
    private val narrowPanel = JPanel(narrowLayout)
    private val narrowLibraryHost = JPanel(BorderLayout())
    private val narrowDetailHost = JPanel(BorderLayout())
    private var renderedDetail: RenderedDetail = RenderedDetail.None
    private var narrowMode = false

    init {
        layout = outerLayout
        searchField.textEditor.accessibleContext.accessibleName = "Search prompt templates"
        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = controller.onSearchChanged()
        })

        backButton.isVisible = false
        backButton.addActionListener { showNarrowLibrary() }
        detailCards.add(createEmptyState(), BorderLayout.CENTER)
        detailWrapper.add(backButton, BorderLayout.NORTH)
        detailWrapper.add(detailCards, BorderLayout.CENTER)

        widePanel.add(wideSplitter, BorderLayout.CENTER)
        narrowPanel.add(narrowLibraryHost, NARROW_LIBRARY_CARD)
        narrowPanel.add(narrowDetailHost, NARROW_DETAIL_CARD)
        add(widePanel, WIDE_CARD)
        add(narrowPanel, NARROW_CARD)
        attachWide()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateResponsiveLayout()
        })
        controller.start(this)
    }

    fun focusSearch() {
        if (narrowMode) showNarrowLibrary()
        searchField.textEditor.requestFocusInWindow()
    }

    fun startNewTemplate() = controller.startNewTemplate()

    fun copyRenderedPrompt() = controller.performUseViewAction(UseViewAction.COPY_PROMPT)

    fun insertRenderedPrompt() = controller.performUseViewAction(UseViewAction.INSERT)

    fun hasValidRenderedPrompt(): Boolean = controller.hasValidRenderedPrompt()

    override val selectedDestinationFolder: Path
        get() = libraryTree.selectedDestinationFolder()

    override fun renderLibrary(
        snapshot: LibrarySnapshot,
        bodyIndex: Map<Path, String>,
        selectedKey: LibrarySelectionKey?,
        expandedPaths: Collection<String>,
    ) {
        val diagnostic = snapshot.diagnostic?.takeIf(String::isNotBlank)
        libraryDiagnosticLabel.text = diagnostic.orEmpty()
        libraryDiagnosticLabel.toolTipText = diagnostic
        libraryDiagnosticLabel.isVisible = diagnostic != null

        libraryTree.updateLibrary(
            snapshot = snapshot,
            bodyIndex = bodyIndex,
            searchQuery = searchField.text,
            selectedKey = selectedKey,
            expandedPaths = expandedPaths,
        )
    }

    override fun clearLibrarySelection() = libraryTree.clearSelection()

    override fun renderDetail(detail: PromptDetailState) {
        disposeRenderedDetail()
        when (detail) {
            PromptDetailState.Empty -> {
                replaceDetail(createEmptyState())
                showNarrowLibrary()
            }
            is PromptDetailState.Folder -> renderFolder(detail.entry)
            is PromptDetailState.Use -> renderUse(detail)
            is PromptDetailState.Author -> renderAuthor(detail.author)
            is PromptDetailState.LoadError -> renderError(detail)
        }
    }

    override fun updateUsePreview(detail: PromptDetailState.Use) {
        val useView = renderedDetail as? RenderedDetail.Use ?: return
        if (useView.previewField.text != detail.render.renderedText) useView.previewField.text = detail.render.renderedText
        useView.actionButtons[UseViewAction.INSERT]?.text = detail.session.insertionLabel
        useView.actionButtons[UseViewAction.COPY_PROMPT]?.isEnabled = !detail.session.capturing
        useView.actionButtons[UseViewAction.INSERT]?.isEnabled = !detail.session.capturing
        useView.highlights.update(detail.render)
        useView.validationLabel.text = detail.session.deliveryProblem.orEmpty()
        useView.contextArea.text = if (detail.referencedContext.isEmpty()) {
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
            useView.contextArea.append("\nContext changed. Refresh Context to capture it; this preview is unchanged.")
        }
    }

    override fun focusVariable(key: String) {
        (renderedDetail as? RenderedDetail.Use)?.dynamicForm?.focusVariable(key)
    }

    override fun setInteractionState(mutationsEnabled: Boolean, authorOpen: Boolean) {
        libraryTree.setMutationsEnabled(mutationsEnabled)
        mutationControls.forEach { it.isEnabled = mutationsEnabled }
        returnToAuthorButton.isVisible = narrowMode && authorOpen
    }

    override fun showNarrowDetail() {
        if (narrowMode) narrowLayout.show(narrowPanel, NARROW_DETAIL_CARD)
    }

    private fun createLibraryPanel(): JPanel {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        val newButton = JButton("New")
        newButton.accessibleContext.accessibleName = "New prompt template or folder"
        newButton.addActionListener {
            val popup = JPopupMenu().apply {
                add(JMenuItem("New Template").apply { addActionListener { controller.startNewTemplate() } })
                add(JMenuItem("New Folder").apply {
                    addActionListener {
                        controller.performLibraryCommand(
                            LibraryTreeCommand.NEW_FOLDER,
                            libraryTree.selectedSelection()
                                ?: LibraryTreeSelection.Root(selectedDestinationFolder),
                        )
                    }
                })
            }
            popup.show(newButton, 0, newButton.height)
        }
        val importButton = JButton("Import")
        importButton.addActionListener { controller.importMarkdown() }
        mutationControls += newButton
        mutationControls += importButton
        actions.add(newButton)
        actions.add(importButton)

        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6)
            add(searchField, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(libraryDiagnosticLabel, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(400))
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(libraryTree), BorderLayout.CENTER)
            add(returnToAuthorButton, BorderLayout.SOUTH)
        }
    }

    private fun createEmptyState(): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(28)
        }
        content.add(JBLabel("No prompt template selected.").apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(JButton("New Template").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { controller.startNewTemplate() }
        })
        content.add(Box.createVerticalStrut(JBUI.scale(6)))
        content.add(JButton("Import Markdown…").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { controller.importMarkdown() }
        })
        return content
    }

    private fun updateResponsiveLayout() {
        val shouldBeNarrow = width in 1 until JBUI.scale(640)
        if (shouldBeNarrow == narrowMode) return
        if (shouldBeNarrow) attachNarrow() else attachWide()
    }

    private fun attachWide() {
        narrowLibraryHost.remove(libraryPanel)
        narrowDetailHost.remove(detailWrapper)
        wideSplitter.firstComponent = libraryPanel
        wideSplitter.secondComponent = detailWrapper
        backButton.isVisible = false
        narrowMode = false
        returnToAuthorButton.isVisible = false
        outerLayout.show(this, WIDE_CARD)
        revalidate()
    }

    private fun attachNarrow() {
        wideSplitter.firstComponent = null
        wideSplitter.secondComponent = null
        narrowLibraryHost.add(libraryPanel, BorderLayout.CENTER)
        narrowDetailHost.add(detailWrapper, BorderLayout.CENTER)
        backButton.isVisible = true
        narrowMode = true
        returnToAuthorButton.isVisible = controller.authorOpen
        outerLayout.show(this, NARROW_CARD)
        if (controller.authorOpen || renderedDetail is RenderedDetail.Use) showNarrowDetail() else showNarrowLibrary()
        revalidate()
    }

    private fun showNarrowLibrary() {
        if (narrowMode) narrowLayout.show(narrowPanel, NARROW_LIBRARY_CARD)
    }

    private fun renderFolder(folder: LibraryEntry.Folder) {
        val templateCount = flattenTemplates(folder.children).size
        val folderCount = flattenFolders(folder.children).size
        val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(18)
        }
        val title = JBLabel(folder.displayName).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        val description = buildString {
            append(portableRelativePath(settings.libraryRoot, folder.directory))
            append("\n$templateCount template${if (templateCount == 1) "" else "s"}")
            append(" · $folderCount nested folder${if (folderCount == 1) "" else "s"}")
        }
        val details = JBTextArea(description).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            accessibleContext.accessibleName = "Selected folder details"
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JButton("New Template").apply {
                addActionListener { controller.startNewTemplateAt(folder.directory) }
            })
            add(JButton("New Folder").apply {
                addActionListener {
                    controller.performLibraryCommand(
                        LibraryTreeCommand.NEW_FOLDER,
                        LibraryTreeSelection.Folder(folder),
                    )
                }
            })
            add(JButton("Import Markdown…").apply {
                addActionListener { controller.importMarkdown(folder.directory) }
            })
        }
        panel.add(title, BorderLayout.NORTH)
        panel.add(details, BorderLayout.CENTER)
        panel.add(actions, BorderLayout.SOUTH)
        replaceDetail(panel)
        showNarrowLibrary()
    }

    private fun renderUse(detail: PromptDetailState.Use) {
        val stored = detail.stored
        val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
        }
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
        panel.add(header, BorderLayout.NORTH)

        val variableAccents = VariableAccentPalette.forVariables(stored.template.metadata.variables)
        val inputVariables = referencedUserVariables(stored.template)
        val dynamicForm = DynamicVariableForm(
            inputVariables,
            variableAccents,
            detail.values,
            controller::setInvocationValue,
        )
        val formPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(dynamicForm).apply {
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
        val previewField = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setViewer(true)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(190))
            accessibleContext.accessibleName = "Rendered prompt preview"
            addSettingsProvider { editor ->
                editor.settings.isUseSoftWraps = true
                configurePromptEditorScrollbars(editor.scrollPane)
            }
        }
        val contextArea = JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            accessibleContext.accessibleName = "Resolved context"
        }
        val validationLabel = JBLabel().apply { foreground = com.intellij.ui.JBColor.RED }
        val previewPanel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))).apply {
            add(contextArea, BorderLayout.NORTH)
            add(previewField, BorderLayout.CENTER)
            add(validationLabel, BorderLayout.SOUTH)
        }
        panel.add(
            createUseViewContent(inputVariables.isNotEmpty(), formPanel, previewPanel),
            BorderLayout.CENTER,
        )
        val actionButtons = mutableMapOf<UseViewAction, JButton>()
        panel.add(createUseActions(actionButtons), BorderLayout.SOUTH)

        renderedDetail = RenderedDetail.Use(
            previewField,
            validationLabel,
            contextArea,
            dynamicForm,
            RenderedVariableHighlightController(previewField, variableAccents),
            actionButtons,
        )
        replaceDetail(panel)
        updateUsePreview(detail)
        showNarrowDetail()
    }

    private fun createUseActions(buttons: MutableMap<UseViewAction, JButton>): JComponent {
        val primary = ResponsiveActionsPanel()
        USE_VIEW_PRIMARY_ACTIONS.forEach { action ->
            primary.add(JButton(action.label).apply {
                if (action == UseViewAction.COPY_PROMPT) font = JBUI.Fonts.label().asBold()
                buttons[action] = this
                addActionListener { controller.performUseViewAction(action) }
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
                addActionListener { controller.performUseViewAction(action) }
            })
        }
        return JButton("File ▾").apply {
            accessibleContext.accessibleName = "Template file actions"
            addActionListener { popup.show(this, 0, height) }
        }
    }

    private fun renderAuthor(author: TemplateAuthorState) {
        val panel = TemplateAuthorPanel(
            project = project,
            initialDraft = author.draft,
            onSave = controller::saveDraft,
            onCancel = controller::cancelAuthor,
        )
        renderedDetail = RenderedDetail.Author(panel)
        replaceDetail(panel)
        showNarrowDetail()
    }

    override fun confirmDiscardAuthor(): Boolean =
        (renderedDetail as? RenderedDetail.Author)?.panel?.confirmDiscardChanges() ?: true

    private fun renderError(error: PromptDetailState.LoadError) {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(18)
            add(JBLabel("Unable to open ${error.templateName}"), BorderLayout.NORTH)
            add(JBScrollPane(JBTextArea(error.message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                caretPosition = 0
                accessibleContext.accessibleName = error.message
            }), BorderLayout.CENTER)
        }
        replaceDetail(panel)
        showNarrowDetail()
    }

    private fun replaceDetail(component: JComponent) {
        detailCards.removeAll()
        detailCards.add(component, BorderLayout.CENTER)
        detailCards.revalidate()
        detailCards.repaint()
    }

    private fun disposeRenderedDetail() {
        when (val detail = renderedDetail) {
            RenderedDetail.None -> Unit
            is RenderedDetail.Author -> Disposer.dispose(detail.panel)
            is RenderedDetail.Use -> detail.highlights.dispose()
        }
        renderedDetail = RenderedDetail.None
    }

    override fun dispose() {
        controller.dispose()
        coroutineScope.cancel()
        settings.splitterProportion = wideSplitter.proportion
        disposeRenderedDetail()
    }

    private sealed interface RenderedDetail {
        data object None : RenderedDetail

        data class Author(val panel: TemplateAuthorPanel) : RenderedDetail

        data class Use(
            val previewField: EditorTextField,
            val validationLabel: JBLabel,
            val contextArea: JBTextArea,
            val dynamicForm: DynamicVariableForm,
            val highlights: RenderedVariableHighlightController,
            val actionButtons: Map<UseViewAction, JButton>,
        ) : RenderedDetail
    }

    private companion object {
        const val WIDE_CARD = "wide"
        const val NARROW_CARD = "narrow"
        const val NARROW_LIBRARY_CARD = "narrow-library"
        const val NARROW_DETAIL_CARD = "narrow-detail"
    }
}

internal enum class UseViewAction(val label: String) {
    COPY_PROMPT("Copy Prompt"),
    INSERT("Insert…"),
    EDIT("Edit"),
    OPEN_MARKDOWN("Open Markdown"),
    REVEAL("Reveal in File Manager"),
    COPY_PATH("Copy Markdown Path"),
    EXPORT_TEMPLATE("Export Template Markdown…"),
    EXPORT_RENDERED("Export Rendered Markdown…"),
    DELETE("Delete"),
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
    UseViewAction.REFRESH_CONTEXT,
    UseViewAction.RELOAD_TEMPLATE,
    UseViewAction.SELECT_INSERTION_TARGET,
    UseViewAction.RESET_VALUES,
    UseViewAction.OPEN_MARKDOWN,
    UseViewAction.REVEAL,
    UseViewAction.COPY_PATH,
    UseViewAction.EXPORT_TEMPLATE,
    UseViewAction.EXPORT_RENDERED,
    UseViewAction.DELETE,
)
