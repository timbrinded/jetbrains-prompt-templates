package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.context.PromptContextResolver
import dev.timbrinded.prompttemplates.core.ContextValue
import dev.timbrinded.prompttemplates.core.DiagnosticSeverity
import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.LinearPlaceholderParser
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.RenderResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.StrictPromptRenderer
import dev.timbrinded.prompttemplates.core.TemplateDiagnostic
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.core.TemplateSummary
import dev.timbrinded.prompttemplates.core.defaultVariableLabel
import dev.timbrinded.prompttemplates.destination.ActiveEditorDestination
import dev.timbrinded.prompttemplates.destination.ClipboardDestination
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class PromptTemplatesPanel(
    private val project: Project,
) : JPanel(), Disposable {
    private val settings = PromptTemplatesSettings.getInstance()
    private var repository = FileSystemPromptTemplateRepository(settings.libraryRoot)
    private val renderer = StrictPromptRenderer()
    private val parser = LinearPlaceholderParser()
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
        onSelection = ::onLibrarySelection,
        onCommand = ::performLibraryCommand,
        onMove = ::moveEntry,
        onExpansionChanged = { expanded ->
            settings.state.expandedFolderPaths.clear()
            settings.state.expandedFolderPaths.addAll(expanded)
        },
    )
    private val libraryPanel = createLibraryPanel()
    private val detailLayout = CardLayout()
    private val detailCards = JPanel(detailLayout)
    private val detailWrapper = JPanel(BorderLayout())
    private val backButton = JButton("‹ Library")
    private val outerLayout = CardLayout()
    private val widePanel = JPanel(BorderLayout())
    private val wideSplitter = OnePixelSplitter(false, settings.state.splitterProportion)
    private val narrowPanel = JPanel(CardLayout())
    private val narrowLibraryHost = JPanel(BorderLayout())
    private val narrowDetailHost = JPanel(BorderLayout())
    private var librarySnapshot = LibrarySnapshot(settings.libraryRoot, emptyList())
    private val bodyIndex = mutableMapOf<Path, String>()
    private val sessionValues = mutableMapOf<TemplateId, MutableMap<String, String>>()
    private val loadGenerations = LoadGenerationTracker()
    private val preferredSelections = PreferredLibrarySelectionTracker()
    private val authorRequests = AuthorAsyncRequestTracker()
    private var libraryFileWatcher: LibraryFileWatcher? = null
    private var watchedLibraryRoot: Path? = null
    private var narrowMode = false
    @Volatile
    private var disposed = false
    private var activeStored: StoredTemplate? = null
    private var activeTemplateTarget: TemplateDetailTarget? = null
    private var activeFolderDirectory: Path? = null
    private var activeContext: Map<String, ContextValue> = emptyMap()
    private var activeRender: RenderResult? = null
    private var previewField: EditorTextField? = null
    private var validationLabel: JBLabel? = null
    private var contextArea: JBTextArea? = null
    private var dynamicForm: DynamicVariableForm? = null
    private var previewHighlights: RenderedVariableHighlightController? = null
    private var currentAuthor: TemplateAuthorPanel? = null
    private var authorSession: AuthorSessionState? = null
    private var mutationInProgress = false
    private var refreshingLibraryTree = false

    init {
        layout = outerLayout
        searchField.textEditor.accessibleContext.accessibleName = "Search prompt templates"
        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refreshTree()
        })

        backButton.isVisible = false
        backButton.addActionListener { showNarrowLibrary() }
        detailCards.add(createEmptyState(), EMPTY_CARD)
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
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            PromptTemplatesSettingsListener.TOPIC,
            PromptTemplatesSettingsListener(::onLibraryRootChanged),
        )
        bindLibraryFileWatcher(settings.libraryRoot)
        reloadLibrary()
    }

    fun focusSearch() {
        if (narrowMode) showNarrowLibrary()
        searchField.textEditor.requestFocusInWindow()
    }

    fun startNewTemplate() {
        startNewTemplateAt(libraryTree.selectedDestinationFolder())
    }

    private fun startNewTemplateAt(destination: Path) {
        if (!canChangeLibrary()) return
        showAuthor(
            PromptTemplateDraft(
                name = "New prompt",
                markdown = "# New prompt\n\n{{objective}}\n",
            ),
            existing = null,
            destination = destination,
        )
    }

    fun copyRenderedPrompt() = deliver(copy = true)

    fun insertRenderedPrompt() = deliver(copy = false)

    fun hasValidRenderedPrompt(): Boolean = activeRender?.isValid == true && activeStored != null

    private fun createLibraryPanel(): JPanel {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        val newButton = JButton("New")
        newButton.accessibleContext.accessibleName = "New prompt template or folder"
        newButton.addActionListener {
            val popup = JPopupMenu().apply {
                add(JMenuItem("New Template").apply { addActionListener { startNewTemplate() } })
                add(JMenuItem("New Folder").apply {
                    addActionListener { createFolder(libraryTree.selectedDestinationFolder()) }
                })
            }
            popup.show(newButton, 0, newButton.height)
        }
        val importButton = JButton("Import")
        importButton.addActionListener { importMarkdown(libraryTree.selectedDestinationFolder()) }
        mutationControls += newButton
        mutationControls += importButton
        actions.add(newButton)
        actions.add(importButton)

        val header = JPanel(BorderLayout(JBUI.scale(6), 0))
        header.border = JBUI.Borders.empty(6)
        header.add(searchField, BorderLayout.CENTER)
        header.add(actions, BorderLayout.EAST)

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
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(28)
        JBLabel("No prompt template selected.").also {
            it.alignmentX = Component.LEFT_ALIGNMENT
            content.add(it)
        }
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        JButton("New Template").also { button ->
            button.alignmentX = Component.LEFT_ALIGNMENT
            button.addActionListener { startNewTemplate() }
            content.add(button)
        }
        content.add(Box.createVerticalStrut(JBUI.scale(6)))
        JButton("Import Markdown…").also { button ->
            button.alignmentX = Component.LEFT_ALIGNMENT
            button.addActionListener { importMarkdown(libraryTree.selectedDestinationFolder()) }
            content.add(button)
        }
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
        updateAuthorReturnVisibility()
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
        updateAuthorReturnVisibility()
        outerLayout.show(this, NARROW_CARD)
        if (activeStored == null && currentAuthor == null) showNarrowLibrary() else showNarrowDetail()
        revalidate()
    }

    private fun showNarrowLibrary() {
        if (narrowMode) (narrowPanel.layout as CardLayout).show(narrowPanel, NARROW_LIBRARY_CARD)
    }

    private fun showNarrowDetail() {
        if (narrowMode) (narrowPanel.layout as CardLayout).show(narrowPanel, NARROW_DETAIL_CARD)
    }

    private fun onLibraryFilesChanged() {
        ApplicationManager.getApplication().invokeLater {
            if (!isPanelDisposed()) reloadLibrary(reloadSelectedDetail = true)
        }
    }

    private fun onLibraryRootChanged(root: Path) {
        val applyChange = {
            if (!isPanelDisposed() && hasLibraryRootChanged(librarySnapshot.root, root)) {
                applyLibraryRootTransition(root, clearTree = true)
                reloadLibrary()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            applyChange()
        } else {
            ApplicationManager.getApplication().invokeLater {
                applyChange()
            }
        }
    }

    private fun applyLibraryRootTransition(root: Path, clearTree: Boolean) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        bindLibraryFileWatcher(normalizedRoot)
        repository = FileSystemPromptTemplateRepository(normalizedRoot)
        preferredSelections.cancel()
        loadGenerations.invalidateDetailLoad()
        authorRequests.invalidate()
        settings.state.selectedTemplateId = null
        settings.state.expandedFolderPaths.clear()
        if (currentAuthor == null) {
            clearSelectedTemplate()
        } else {
            activeStored = null
            activeTemplateTarget = null
            activeFolderDirectory = null
            if (authorSession?.rebaseAsNewTemplate(normalizedRoot) == true) {
                PromptTemplatesNotifications.warning(
                    project,
                    "The library location changed. The open draft is unchanged and will save as a new template in the new library.",
                )
            }
        }
        if (clearTree) {
            librarySnapshot = LibrarySnapshot(normalizedRoot, emptyList())
            bodyIndex.clear()
            updateLibraryDiagnostic(null)
            refreshTree(null)
        }
    }

    private fun bindLibraryFileWatcher(root: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!shouldRebindLibraryWatcher(watchedLibraryRoot, normalizedRoot)) return
        libraryFileWatcher?.let(Disposer::dispose)
        libraryFileWatcher = LibraryFileWatcher(project, normalizedRoot, this, ::onLibraryFilesChanged)
        watchedLibraryRoot = normalizedRoot
    }

    private fun reloadLibrary(
        preferredSelection: LibrarySelectionKey? = null,
        reloadSelectedDetail: Boolean = false,
    ) {
        preferredSelection?.let(preferredSelections::remember)
        val generation = loadGenerations.beginLibraryLoad()
        val nextRepository = FileSystemPromptTemplateRepository(settings.libraryRoot)
        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = nextRepository.scan()
            val templates = flattenTemplates(scanned.children)
            val folders = flattenFolders(scanned.children)
            val indexedBodies = templates.associate { entry ->
                val markdownPath = entry.summary.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
                entry.summary.directory to readSearchIndexBody(markdownPath)
            }
            ApplicationManager.getApplication().invokeLater {
                if (isPanelDisposed() || !loadGenerations.isCurrentLibraryLoad(generation)) return@invokeLater
                val rootChanged = hasLibraryRootChanged(librarySnapshot.root, scanned.root)
                if (rootChanged) {
                    applyLibraryRootTransition(scanned.root, clearTree = false)
                }
                repository = nextRepository
                librarySnapshot = scanned
                updateLibraryDiagnostic(libraryDiagnostic(scanned))
                bodyIndex.clear()
                bodyIndex.putAll(indexedBodies)
                val pendingDetailBeforeRefresh = loadGenerations.pendingDetailLoad()
                if (pendingDetailBeforeRefresh != null) loadGenerations.invalidateDetailLoad()
                val folderDetailBeforeRefresh = activeFolderDirectory
                refreshTree(preferredSelections.preferredOr(selectionAfterLibraryReload()))
                val selected = libraryTree.selectedSelection() as? LibraryTreeSelection.Template
                val active = activeStored
                val activeEntry = active?.let { stored ->
                    resolveTemplateEntry(
                        activeTemplateTarget ?: TemplateDetailTarget(stored.directory, stored.template.id.value),
                        templates,
                    )
                }
                val pendingEntry = pendingDetailBeforeRefresh?.let { request ->
                    resolveTemplateEntry(request.target, templates)
                }
                if (
                    pendingDetailBeforeRefresh != null &&
                    pendingEntry != null &&
                    shouldRestartPendingDetailAfterReload(
                        resolvedPendingDirectory = pendingEntry.directory,
                        selectedTemplateDirectory = selected?.directory,
                        authorOpen = currentAuthor != null,
                    )
                ) {
                    startTemplateDetailLoad(pendingEntry.summary, pendingDetailBeforeRefresh.intent)
                }
                if (
                    loadGenerations.pendingDetailLoad() == null &&
                    selected != null &&
                    shouldReloadSelectedDetail(
                        reloadRequested = reloadSelectedDetail,
                        authorOpen = currentAuthor != null,
                        selectedDirectory = selected.directory,
                        activeDirectory = activeEntry?.directory,
                    )
                ) {
                    loadTemplate(selected.entry.summary)
                } else if (
                    loadGenerations.pendingDetailLoad() == null &&
                    shouldReloadHiddenActiveDetail(
                        reloadRequested = reloadSelectedDetail,
                        authorOpen = currentAuthor != null,
                        selectedDirectory = selected?.directory,
                        activeDirectory = active?.directory,
                    ) && activeEntry != null
                ) {
                    loadTemplate(activeEntry.summary)
                }
                val differentTemplateIsLoading = selected != null && active?.directory != selected.directory
                if (active != null && activeEntry == null && !differentTemplateIsLoading && currentAuthor == null) {
                    clearSelectedTemplate()
                } else if (
                    shouldReconcileFolderDetailAfterReload(
                        templateActive = active != null,
                        authorOpen = currentAuthor != null,
                        previousFolderDirectory = folderDetailBeforeRefresh,
                        currentFolderDirectory = activeFolderDirectory,
                    )
                ) {
                    val refreshedFolder = folders.firstOrNull { it.directory == folderDetailBeforeRefresh }
                    when {
                        refreshedFolder == null -> clearSelectedTemplate()
                        (libraryTree.selectedSelection() as? LibraryTreeSelection.Folder)?.directory !=
                            folderDetailBeforeRefresh -> showFolder(refreshedFolder)
                    }
                }
            }
        }
    }

    private fun selectionAfterLibraryReload(): LibrarySelectionKey? {
        val activeSelection = activeStored?.let { stored ->
            activeTemplateSelection(
                root = librarySnapshot.root,
                activeDirectory = stored.directory,
                templateId = activeTemplateTarget?.templateId,
            )
        }
        return selectLibrarySelectionAfterReload(
            authorOpen = currentAuthor != null,
            currentSelection = libraryTree.currentSelectionKey(),
            activeSelection = activeSelection,
            persistedTemplateId = settings.state.selectedTemplateId,
        )
    }

    private fun refreshTree(preferredSelection: LibrarySelectionKey? = libraryTree.currentSelectionKey()) {
        val pendingSelection = preferredSelections.pendingSelection()
        val selectionToRestore = pendingSelection ?: preferredSelection
        refreshingLibraryTree = true
        try {
            libraryTree.updateLibrary(
                snapshot = librarySnapshot,
                bodyIndex = bodyIndex,
                searchQuery = searchField.text,
                preferredSelection = selectionToRestore,
                expandedPaths = settings.state.expandedFolderPaths,
                preferPreferredSelection = pendingSelection != null,
            )
        } finally {
            refreshingLibraryTree = false
        }
        preferredSelections.acknowledge(libraryTree.currentSelectionKey())
    }

    private fun updateLibraryDiagnostic(message: String?) {
        libraryDiagnosticLabel.text = message.orEmpty()
        libraryDiagnosticLabel.toolTipText = message
        libraryDiagnosticLabel.isVisible = message != null
    }

    private fun onLibrarySelection(selection: LibraryTreeSelection) {
        if (!refreshingLibraryTree) preferredSelections.cancel()
        if (currentAuthor != null) {
            if (narrowMode) showNarrowDetail()
            return
        }
        when (selection) {
            is LibraryTreeSelection.Template -> {
                activeFolderDirectory = null
                settings.state.selectedTemplateId = selection.entry.summary.id?.value
                    ?.takeIf { selection.entry.summary.health == TemplateHealth.HEALTHY }
                if (activeStored?.directory == selection.directory) return
                loadTemplate(selection.entry.summary)
            }
            is LibraryTreeSelection.Folder -> {
                settings.state.selectedTemplateId = null
                showFolder(selection.entry)
            }
            is LibraryTreeSelection.Root -> clearSelectedTemplate()
        }
    }

    private fun loadTemplate(summary: TemplateSummary) {
        startTemplateDetailLoad(summary, TemplateDetailIntent.USE)
    }

    private fun startTemplateDetailLoad(summary: TemplateSummary, intent: TemplateDetailIntent) {
        val request = loadGenerations.beginDetailLoad(
            target = TemplateDetailTarget(summary.directory, summary.id?.value),
            intent = intent,
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = repository.load(summary.directory)
            val directoryMissing = Files.notExists(summary.directory)
            ApplicationManager.getApplication().invokeLater {
                if (isPanelDisposed() || !loadGenerations.acceptDetailLoad(request)) return@invokeLater
                when (result) {
                    is RepositoryResult.Success -> when (intent) {
                        TemplateDetailIntent.USE -> showUse(result.value, request.target)
                        TemplateDetailIntent.EDIT -> editStored(result.value, request.target)
                    }
                    is RepositoryResult.Failure -> when (detailLoadFailureAction(result, directoryMissing)) {
                        DetailLoadFailureAction.CLEAR_AND_RELOAD -> {
                            clearSelectedTemplate()
                            reloadLibrary(preferredSelection = null)
                        }
                        DetailLoadFailureAction.SHOW_ERROR -> showError(summary.name, result.message)
                    }
                }
            }
        }
    }

    private fun showFolder(folder: LibraryEntry.Folder) {
        loadGenerations.invalidateDetailLoad()
        disposeUseView()
        activeStored = null
        activeTemplateTarget = null
        activeFolderDirectory = folder.directory
        activeRender = null

        val templateCount = flattenTemplates(folder.children).size
        val folderCount = countFolders(folder.children)
        val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(18)
        }
        val title = JBLabel(folder.displayName).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        val description = buildString {
            append(portableRelativePath(librarySnapshot.root, folder.directory))
            append("\n")
            append("$templateCount template${if (templateCount == 1) "" else "s"}")
            append(" · $folderCount nested folder${if (folderCount == 1) "" else "s"}")
        }
        val details = JBTextArea(description).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            accessibleContext.accessibleName = "Selected folder details"
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JButton("New Template").apply { addActionListener { startNewTemplateAt(folder.directory) } })
            add(JButton("New Folder").apply { addActionListener { createFolder(folder.directory) } })
            add(JButton("Import Markdown…").apply { addActionListener { importMarkdown(folder.directory) } })
        }
        panel.add(title, BorderLayout.NORTH)
        panel.add(details, BorderLayout.CENTER)
        panel.add(actions, BorderLayout.SOUTH)
        replaceDetail(FOLDER_CARD, panel)
        if (narrowMode) showNarrowLibrary()
    }

    private fun showUse(
        stored: StoredTemplate,
        target: TemplateDetailTarget = TemplateDetailTarget(stored.directory, stored.template.id.value),
    ) {
        loadGenerations.invalidateDetailLoad()
        disposeAuthor()
        disposeUseView()
        activeStored = stored
        activeTemplateTarget = target
        activeFolderDirectory = null
        settings.markRecent(stored.template.id.value)
        val values = sessionValues.getOrPut(stored.template.id) { mutableMapOf() }
        activeContext = PromptContextResolver.resolve(project)

        val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
        panel.border = JBUI.Borders.empty(10)
        val header = JPanel(BorderLayout(JBUI.scale(8), 0))
        val title = JBLabel(stored.template.metadata.name)
        title.font = title.font.deriveFont(title.font.style or java.awt.Font.BOLD)
        val titleRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(createFileActionsMenu(), BorderLayout.EAST)
        }
        header.add(titleRow, BorderLayout.NORTH)
        header.add(JBLabel(stored.directory.resolve("prompt.md").toString()), BorderLayout.SOUTH)
        panel.add(header, BorderLayout.NORTH)

        val variableAccents = VariableAccentPalette.forVariables(stored.template.metadata.variables)
        dynamicForm = DynamicVariableForm(
            stored.template.metadata.variables,
            variableAccents,
            values,
            ::refreshPreview,
        )
        val formPanel = JPanel(BorderLayout())
        formPanel.add(JBScrollPane(dynamicForm), BorderLayout.CENTER)

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
        previewHighlights = RenderedVariableHighlightController(previewField!!, variableAccents)
        contextArea = JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            accessibleContext.accessibleName = "Resolved context"
        }
        validationLabel = JBLabel()
        validationLabel!!.foreground = com.intellij.ui.JBColor.RED
        val previewPanel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6)))
        previewPanel.add(contextArea, BorderLayout.NORTH)
        previewPanel.add(previewField, BorderLayout.CENTER)
        previewPanel.add(validationLabel, BorderLayout.SOUTH)

        panel.add(
            createUseViewContent(stored.template.metadata.variables.isNotEmpty(), formPanel, previewPanel),
            BorderLayout.CENTER,
        )
        panel.add(createUseActions(), BorderLayout.SOUTH)

        replaceDetail(USE_CARD, panel)
        refreshPreview()
        showNarrowDetail()
    }

    private fun createUseActions(): JComponent {
        val primary = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        USE_VIEW_PRIMARY_ACTIONS.forEach { action ->
            JButton(action.label).also { button ->
                button.addActionListener { performUseViewAction(action) }
                primary.add(button)
            }
        }

        val destructive = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        JButton(UseViewAction.DELETE.label).also { button ->
            button.addActionListener { performUseViewAction(UseViewAction.DELETE) }
            destructive.add(button)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(primary, BorderLayout.WEST)
            add(destructive, BorderLayout.EAST)
        }
    }

    private fun createFileActionsMenu(): JComponent {
        val popup = JPopupMenu()
        USE_VIEW_FILE_ACTIONS.forEach { action ->
            if (action == UseViewAction.EXPORT_TEMPLATE) popup.addSeparator()
            popup.add(
                JMenuItem(action.label).apply {
                    addActionListener { performUseViewAction(action) }
                },
            )
        }
        return JButton("File ▾").apply {
            accessibleContext.accessibleName = "Template file actions"
            addActionListener { popup.show(this, 0, height) }
        }
    }

    private fun performUseViewAction(action: UseViewAction) {
        when (action) {
            UseViewAction.COPY_PROMPT -> copyRenderedPrompt()
            UseViewAction.INSERT -> insertRenderedPrompt()
            UseViewAction.EDIT -> editActive()
            UseViewAction.OPEN_MARKDOWN -> openMarkdown()
            UseViewAction.REVEAL -> revealSource()
            UseViewAction.COPY_PATH -> copyMarkdownPath()
            UseViewAction.EXPORT_TEMPLATE -> exportTemplate()
            UseViewAction.EXPORT_RENDERED -> exportRendered()
            UseViewAction.DELETE -> deleteActive()
        }
    }

    private fun refreshPreview() {
        val stored = activeStored ?: return
        val values = sessionValues.getOrPut(stored.template.id) { mutableMapOf() }
        activeRender = renderer.render(stored.template, values, activeContext)
        previewField?.text = activeRender!!.renderedText
        previewHighlights?.update(activeRender!!)
        val firstError = activeRender!!.diagnostics.firstOrNull { it.severity == DiagnosticSeverity.ERROR }
        validationLabel?.text = firstError?.message.orEmpty()
        val referencedContext = parser.parse(stored.template.markdown).placeholders
            .filter { it.contextReference }
            .map { it.key }
            .distinct()
        contextArea?.text = if (referencedContext.isEmpty()) {
            ""
        } else {
            referencedContext.joinToString("\n", prefix = "Context\n") { key ->
                val context = activeContext[key]
                if (context?.value != null) "✓ $key — ${context.displaySummary.orEmpty()}" else "! $key — ${context?.errorMessage ?: "unknown"}"
            }
        }
    }

    private fun deliver(copy: Boolean) {
        val stored = activeStored ?: return
        activeContext = PromptContextResolver.resolve(project)
        refreshPreview()
        val render = activeRender ?: return
        if (!render.isValid) {
            val error = render.diagnostics.firstOrNull { it.severity == DiagnosticSeverity.ERROR }
            if (error is TemplateDiagnostic.MissingRequiredValue) dynamicForm?.focusVariable(error.key)
            PromptTemplatesNotifications.error(project, error?.message ?: "The prompt is not valid.")
            return
        }

        val destination = if (copy) {
            ClipboardDestination.deliver(render.renderedText)
        } else {
            ActiveEditorDestination.deliver(project, render.renderedText)
        }
        when (destination) {
            DestinationResult.Success -> PromptTemplatesNotifications.info(
                project,
                if (copy) "Prompt copied to the clipboard." else "Prompt inserted into the active editor.",
            )
            is DestinationResult.Failure -> PromptTemplatesNotifications.error(project, destination.message)
        }
        settings.markRecent(stored.template.id.value)
    }

    private fun editActive() {
        if (!canChangeLibrary()) return
        val stored = activeStored ?: return
        showAuthor(
            PromptTemplateDraft(
                id = stored.template.id,
                name = stored.template.metadata.name,
                description = stored.template.metadata.description,
                tags = stored.template.metadata.tags,
                variables = stored.template.metadata.variables,
                markdown = stored.template.markdown,
            ),
            stored,
            destination = stored.directory.parent,
            existingTarget = activeTemplateTarget,
        )
    }

    private fun showAuthor(
        draft: PromptTemplateDraft,
        existing: StoredTemplate?,
        destination: Path,
        existingTarget: TemplateDetailTarget? = null,
    ) {
        authorRequests.invalidate()
        loadGenerations.invalidateDetailLoad()
        val priorSelection = libraryTree.currentSelectionKey()
        disposeAuthor()
        authorSession = AuthorSessionState(
            existing = existing,
            existingTarget = existingTarget ?: existing?.let {
                TemplateDetailTarget(it.directory, it.template.id.value)
            },
            selectionBefore = priorSelection,
            destination = destination,
        )
        disposeUseView()
        activeStored = null
        activeTemplateTarget = null
        activeFolderDirectory = null
        activeRender = null
        val author = TemplateAuthorPanel(
            project = project,
            initialDraft = draft,
            onSave = ::saveDraft,
            onCancel = ::cancelAuthor,
        )
        currentAuthor = author
        updateMutationAvailability()
        updateAuthorReturnVisibility()
        replaceDetail(AUTHOR_CARD, author)
        showNarrowDetail()
    }

    private fun cancelAuthor() {
        val session = authorSession ?: return
        disposeAuthor()
        replaceDetail(EMPTY_CARD, createEmptyState())
        val existing = session.existing
        val existingTarget = session.existingTarget
        if (existing != null && existingTarget != null) {
            val latestEntry = resolveEditedTemplateAfterCancel(existingTarget, librarySnapshot)
            if (latestEntry == null) {
                clearSelectedTemplate()
                return
            }
            val resolvedSelection = LibrarySelectionKey(
                templateId = latestEntry.summary.id?.value ?: existingTarget.templateId,
                relativePath = portableRelativePath(librarySnapshot.root, latestEntry.directory),
            )
            refreshTree(resolvedSelection)
            if ((libraryTree.selectedSelection() as? LibraryTreeSelection.Template)?.directory != latestEntry.directory) {
                loadTemplate(latestEntry.summary)
            }
            return
        }

        val priorSelection = session.selectionBefore
        if (priorSelection != null) refreshTree(priorSelection) else clearSelectedTemplate()
        if (narrowMode && libraryTree.selectedSelection() !is LibraryTreeSelection.Template) showNarrowLibrary()
    }

    private fun saveDraft(draft: PromptTemplateDraft) {
        val session = authorSession ?: return
        val existing = session.existing
        val request = authorRequests.beginSave(session.destination) ?: return
        val repo = repository
        val libraryRootAtRequest = settings.libraryRoot
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!authorRequests.isCurrent(request)) return@executeOnPooledThread
            if (existing != null) {
                val latest = repo.load(existing.directory)
                val externallyChanged = latest !is RepositoryResult.Success || latest.value.template != existing.template
                if (externallyChanged) {
                    if (isPanelDisposed() || !authorRequests.isCurrent(request)) return@executeOnPooledThread
                    var overwrite = false
                    ApplicationManager.getApplication().invokeAndWait {
                        if (isPanelDisposed() || !authorRequests.isCurrent(request)) return@invokeAndWait
                        overwrite = Messages.showYesNoDialog(
                            project,
                            "The template changed on disk after editing began. Overwrite those changes?",
                            "Prompt Template Changed",
                            "Overwrite with Draft",
                            "Cancel",
                            Messages.getWarningIcon(),
                        ) == Messages.YES
                    }
                    if (!overwrite) {
                        authorRequests.finishSave(request)
                        return@executeOnPooledThread
                    }
                }
            }
            if (!authorRequests.isCurrent(request)) return@executeOnPooledThread
            val result = if (existing == null) {
                repo.create(draft, request.destination)
            } else {
                repo.update(existing.directory, draft)
            }
            ApplicationManager.getApplication().invokeLater {
                if (isPanelDisposed() || !authorRequests.isCurrent(request)) return@invokeLater
                if (hasLibraryRootChanged(libraryRootAtRequest, settings.libraryRoot)) {
                    authorRequests.invalidate()
                    return@invokeLater
                }
                authorRequests.finishSave(request)
                when (result) {
                    is RepositoryResult.Success -> {
                        showWarnings(result.warnings)
                        afterTemplateSaved(
                            savedDirectory = result.value.directory,
                            showSaved = { showUse(result.value) },
                            refreshLibrary = { savedDirectory ->
                                reloadLibrary(
                                    LibrarySelectionKey(
                                        templateId = result.value.template.id.value,
                                        relativePath = portableRelativePath(
                                            libraryRootAtRequest,
                                            savedDirectory ?: result.value.directory,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private fun importMarkdown(destination: Path) {
        if (!canChangeLibrary()) return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("md")
            .withTitle("Import Prompt Template Markdown")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val request = authorRequests.begin(destination)
        ApplicationManager.getApplication().executeOnPooledThread {
            val markdown = runCatching { Files.readString(file.toNioPath()) }
            ApplicationManager.getApplication().invokeLater {
                if (isPanelDisposed() || !authorRequests.isCurrent(request)) return@invokeLater
                markdown.onSuccess { body ->
                    val name = body.lineSequence().map(String::trim)
                        .firstOrNull { it.startsWith("# ") }
                        ?.removePrefix("# ")
                        ?.trim()
                        ?.ifBlank { null }
                        ?: file.nameWithoutExtension
                    val variables = parser.parse(body).placeholders
                        .filterNot { it.contextReference }
                        .map { it.key }
                        .distinct()
                        .map { PromptVariable(it, defaultVariableLabel(it)) }
                    showAuthor(
                        PromptTemplateDraft(name = name, variables = variables, markdown = body),
                        existing = null,
                        destination = request.destination,
                    )
                }.onFailure { PromptTemplatesNotifications.error(project, "Unable to read Markdown: ${it.message}") }
            }
        }
    }

    private fun performLibraryCommand(command: LibraryTreeCommand, target: LibraryTreeSelection) {
        when (command) {
            LibraryTreeCommand.NEW_TEMPLATE -> startNewTemplateAt(destinationFor(target))
            LibraryTreeCommand.NEW_FOLDER -> createFolder(destinationFor(target))
            LibraryTreeCommand.IMPORT_MARKDOWN -> importMarkdown(destinationFor(target))
            LibraryTreeCommand.REFRESH -> reloadLibrary(reloadSelectedDetail = true)
            LibraryTreeCommand.REVEAL -> revealSelection(target)
            LibraryTreeCommand.COPY_PATH -> copySelectionPath(target)
            LibraryTreeCommand.RENAME_FOLDER -> (target as? LibraryTreeSelection.Folder)?.let(::renameFolder)
            LibraryTreeCommand.USE_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let {
                loadTemplate(it.entry.summary)
            }
            LibraryTreeCommand.EDIT_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let(::editTemplate)
            LibraryTreeCommand.MOVE_TO_FOLDER -> if (target !is LibraryTreeSelection.Root) moveToFolder(target)
            LibraryTreeCommand.MOVE_UP -> if (target !is LibraryTreeSelection.Root) moveSibling(target, -1)
            LibraryTreeCommand.MOVE_DOWN -> if (target !is LibraryTreeSelection.Root) moveSibling(target, 1)
            LibraryTreeCommand.OPEN_MARKDOWN -> (target as? LibraryTreeSelection.Template)?.let {
                openMarkdown(it.directory)
            }
            LibraryTreeCommand.DELETE_FOLDER -> (target as? LibraryTreeSelection.Folder)?.let(::deleteFolder)
            LibraryTreeCommand.DELETE_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let(::deleteTemplate)
            LibraryTreeCommand.EXPAND_ALL,
            LibraryTreeCommand.COLLAPSE_ALL,
            LibraryTreeCommand.EXPAND_BRANCH,
            LibraryTreeCommand.COLLAPSE_BRANCH,
            -> Unit // The tree handles presentation-only commands.
        }
    }

    private fun destinationFor(target: LibraryTreeSelection): Path = when (target) {
        is LibraryTreeSelection.Root -> target.directory
        is LibraryTreeSelection.Folder -> target.directory
        is LibraryTreeSelection.Template -> target.directory.parent
    }

    private fun createFolder(parent: Path) {
        if (!canChangeLibrary()) return
        val name = Messages.showInputDialog(
            project,
            "Folder name:",
            "New Prompt Template Folder",
            Messages.getQuestionIcon(),
        )?.trim()?.takeIf(String::isNotEmpty) ?: return
        runRepositoryOperation(
            operation = { repository.createFolder(parent, name) },
            successMessage = "Folder '$name' created.",
            afterSuccess = { directory ->
                reloadLibrary(
                    LibrarySelectionKey(
                        relativePath = portableRelativePath(settings.libraryRoot, directory),
                        folder = true,
                    ),
                )
            },
        )
    }

    private fun renameFolder(target: LibraryTreeSelection.Folder) {
        if (!canChangeLibrary()) return
        val oldName = target.entry.displayName
        val newName = Messages.showInputDialog(
            project,
            "New folder name:",
            "Rename Prompt Template Folder",
            Messages.getQuestionIcon(),
            oldName,
            null,
        )?.trim()?.takeIf(String::isNotEmpty) ?: return
        val oldRelative = portableRelativePath(settings.libraryRoot, target.directory)
        runRepositoryOperation(
            operation = { repository.renameFolder(target.directory, newName) },
            successMessage = "Folder renamed to '$newName'.",
            afterSuccess = { directory ->
                val newRelative = portableRelativePath(settings.libraryRoot, directory)
                settings.state.expandedFolderPaths = remapExpandedPaths(
                    settings.state.expandedFolderPaths,
                    oldRelative,
                    newRelative,
                ).toMutableList()
                reloadLibrary(LibrarySelectionKey(relativePath = newRelative, folder = true))
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun moveToFolder(source: LibraryTreeSelection) {
        if (!canChangeLibrary()) return
        val folders = buildList {
            add(librarySnapshot.root)
            addAll(flattenFolders(librarySnapshot.children).map(LibraryEntry.Folder::directory))
        }.filterNot { candidate ->
            source is LibraryTreeSelection.Folder &&
                (candidate == source.directory || candidate.startsWith(source.directory))
        }
        if (folders.isEmpty()) return
        val options = folders.map { directory ->
            if (directory == librarySnapshot.root) "/ (Library root)"
            else portableRelativePath(librarySnapshot.root, directory)
        }.toTypedArray()
        val currentParent = source.directory.parent
        val initialIndex = folders.indexOf(currentParent).takeIf { it >= 0 } ?: 0
        val choice = Messages.showChooseDialog(
            project,
            "Choose the destination folder.",
            "Move Library Entry",
            Messages.getQuestionIcon(),
            options,
            options[initialIndex],
        )
        if (choice !in options.indices) return
        val destination = folders[choice]
        if (!shouldMoveToFolder(source.directory, destination)) return
        moveEntry(source, destination, EntryPlacement.EndOfKind)
    }

    private fun moveSibling(source: LibraryTreeSelection, direction: Int) {
        if (!canChangeLibrary()) return
        val move = siblingMove(librarySnapshot, source.directory, source is LibraryTreeSelection.Folder, direction)
            ?: return
        moveEntry(source, move.destination, move.placement)
    }

    private fun moveEntry(source: LibraryTreeSelection, destination: Path, placement: EntryPlacement) {
        if (!canChangeLibrary()) return
        val keyBeforeMove = selectionKey(source, librarySnapshot.root)
        val oldRelative = portableRelativePath(librarySnapshot.root, source.directory)
        runRepositoryOperation(
            operation = { repository.moveEntry(source.directory, destination, placement) },
            successMessage = "Library entry moved.",
            afterSuccess = { movedDirectory ->
                val newRelative = portableRelativePath(settings.libraryRoot, movedDirectory)
                if (source is LibraryTreeSelection.Folder) {
                    settings.state.expandedFolderPaths = remapExpandedPaths(
                        settings.state.expandedFolderPaths,
                        oldRelative,
                        newRelative,
                    ).toMutableList()
                }
                val preferred = if (keyBeforeMove?.templateId != null) {
                    keyBeforeMove.copy(relativePath = newRelative)
                } else {
                    LibrarySelectionKey(relativePath = newRelative, folder = source is LibraryTreeSelection.Folder)
                }
                reloadLibrary(preferred)
            },
        )
    }

    private fun editTemplate(target: LibraryTreeSelection.Template) {
        if (!canChangeLibrary()) return
        startTemplateDetailLoad(target.entry.summary, TemplateDetailIntent.EDIT)
    }

    private fun editStored(stored: StoredTemplate, target: TemplateDetailTarget) {
        showAuthor(
            PromptTemplateDraft(
                id = stored.template.id,
                name = stored.template.metadata.name,
                description = stored.template.metadata.description,
                tags = stored.template.metadata.tags,
                variables = stored.template.metadata.variables,
                markdown = stored.template.markdown,
            ),
            stored,
            destination = stored.directory.parent,
            existingTarget = target,
        )
    }

    private fun deleteTemplate(target: LibraryTreeSelection.Template) {
        if (!canChangeLibrary()) return
        if (settings.state.confirmDeletion) {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete '${target.entry.summary.name}' and its source files?",
                "Delete Prompt Template",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        runRepositoryOperation(
            operation = { repository.deleteTemplate(target.directory) },
            successMessage = "Prompt template deleted.",
            afterSuccess = {
                clearSelectedTemplate()
                reloadLibrary(null)
            },
        )
    }

    private fun deleteFolder(target: LibraryTreeSelection.Folder) {
        if (!canChangeLibrary()) return
        mutationInProgress = true
        updateMutationAvailability()
        ApplicationManager.getApplication().executeOnPooledThread {
            val previewResult = repository.previewFolderDeletion(target.directory)
            ApplicationManager.getApplication().invokeLater {
                if (isPanelDisposed()) return@invokeLater
                mutationInProgress = false
                updateMutationAvailability()
                when (previewResult) {
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, previewResult.message)
                    is RepositoryResult.Success -> confirmFolderDeletion(target, previewResult.value)
                }
            }
        }
    }

    private fun confirmFolderDeletion(
        target: LibraryTreeSelection.Folder,
        preview: dev.timbrinded.prompttemplates.core.FolderDeletionPreview,
    ) {
        val name = target.entry.displayName
        val typed = Messages.showInputDialog(
            project,
            "This permanently deletes ${preview.templateCount} template(s), ${preview.folderCount} nested folder(s), " +
                "and ${preview.fileCount} file(s). Type '$name' to continue.",
            "Delete Prompt Template Folder",
            Messages.getWarningIcon(),
        ) ?: return
        if (typed != name) {
            PromptTemplatesNotifications.error(project, "Folder name did not match. Nothing was deleted.")
            return
        }
        runRepositoryOperation(
            operation = { repository.deleteFolder(preview) },
            successMessage = "Folder '$name' deleted.",
            afterSuccess = {
                settings.state.expandedFolderPaths.removeIf { path ->
                    path == portableRelativePath(settings.libraryRoot, target.directory) ||
                        path.startsWith(portableRelativePath(settings.libraryRoot, target.directory) + "/")
                }
                clearSelectedTemplate()
                reloadLibrary(null)
            },
        )
    }

    private fun revealSelection(target: LibraryTreeSelection) {
        val path = when (target) {
            is LibraryTreeSelection.Template -> target.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
            else -> target.directory
        }
        com.intellij.ide.actions.RevealFileAction.openFile(path.toFile())
    }

    private fun copySelectionPath(target: LibraryTreeSelection) {
        val path = when (target) {
            is LibraryTreeSelection.Template -> target.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
            else -> target.directory
        }
        CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
        PromptTemplatesNotifications.info(project, "Path copied.")
    }

    private fun exportTemplate() {
        val stored = activeStored ?: return
        val destination = chooseDestination(slug(stored.template.metadata.name) + ".md") ?: return
        runRepositoryOperation(
            operation = { repository.exportTemplateMarkdown(stored.directory, destination) },
            successMessage = "Template Markdown exported to $destination.",
        )
    }

    private fun exportRendered() {
        activeContext = PromptContextResolver.resolve(project)
        refreshPreview()
        val render = activeRender ?: return
        if (!render.isValid) {
            PromptTemplatesNotifications.error(project, "Complete required values before exporting.")
            return
        }
        val name = activeStored?.template?.metadata?.name ?: "prompt"
        val destination = chooseDestination(slug(name) + "-rendered.md") ?: return
        runRepositoryOperation(
            operation = { repository.exportRenderedMarkdown(render.renderedText, destination) },
            successMessage = "Rendered Markdown exported to $destination.",
        )
    }

    private fun chooseDestination(suggestedName: String): Path? {
        val descriptor = FileSaverDescriptor("Export Markdown", "Choose where to export the Markdown file", "md")
        return FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, suggestedName)
            ?.file
            ?.toPath()
    }

    private fun openMarkdown() {
        val directory = activeStored?.directory ?: return
        openMarkdown(directory)
    }

    private fun openMarkdown(directory: Path) {
        val path = directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (file == null) PromptTemplatesNotifications.error(project, "Unable to find $path.")
        else FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun revealSource() {
        val path = activeStored?.directory?.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE) ?: return
        com.intellij.ide.actions.RevealFileAction.openFile(path.toFile())
    }

    private fun copyMarkdownPath() {
        val path = activeStored?.directory?.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
        PromptTemplatesNotifications.info(project, "Markdown path copied.")
    }

    private fun deleteActive() {
        val stored = activeStored ?: return
        if (settings.state.confirmDeletion) {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete '${stored.template.metadata.name}' and its source files?",
                "Delete Prompt Template",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        runRepositoryOperation(
            operation = { repository.deleteTemplate(stored.directory) },
            successMessage = "Prompt template deleted.",
            afterSuccess = { _ ->
                afterTemplateDeleted(
                    clearSelection = ::clearSelectedTemplate,
                    refreshLibrary = { reloadLibrary(null) },
                )
            },
        )
    }

    private fun <T> runRepositoryOperation(
        operation: () -> RepositoryResult<T>,
        successMessage: String,
        afterSuccess: (T) -> Unit = {},
    ) {
        if (mutationInProgress) return
        mutationInProgress = true
        updateMutationAvailability()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                runRepositoryOperationSafely(operation)
            } catch (cancelled: ProcessCanceledException) {
                resetMutationAfterCancellation()
                throw cancelled
            } catch (cancelled: CancellationException) {
                resetMutationAfterCancellation()
                throw cancelled
            }
            ApplicationManager.getApplication().invokeLater {
                if (isPanelDisposed()) return@invokeLater
                mutationInProgress = false
                updateMutationAvailability()
                when (result) {
                    is RepositoryResult.Success -> {
                        PromptTemplatesNotifications.info(project, successMessage)
                        showWarnings(result.warnings)
                        afterSuccess(result.value)
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private fun resetMutationAfterCancellation() {
        ApplicationManager.getApplication().invokeLater {
            if (!isPanelDisposed()) {
                mutationInProgress = false
                updateMutationAvailability()
            }
        }
    }

    private fun showWarnings(warnings: List<String>) {
        warnings.forEach { PromptTemplatesNotifications.warning(project, it) }
    }

    private fun isPanelDisposed(): Boolean = disposed || project.isDisposed

    private fun updateMutationAvailability() {
        val enabled = currentAuthor == null && !mutationInProgress
        libraryTree.setMutationsEnabled(enabled)
        mutationControls.forEach { it.isEnabled = enabled }
    }

    private fun updateAuthorReturnVisibility() {
        returnToAuthorButton.isVisible = authorReturnVisible(narrowMode, currentAuthor != null)
    }

    private fun canChangeLibrary(): Boolean {
        if (mutationInProgress) return false
        if (currentAuthor == null) return true
        PromptTemplatesNotifications.error(project, "Save or cancel the open template before changing the library.")
        return false
    }

    private fun showError(name: String, message: String) {
        loadGenerations.invalidateDetailLoad()
        disposeUseView()
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(18)
        panel.add(JBLabel("Unable to open $name"), BorderLayout.NORTH)
        panel.add(JBLabel(message), BorderLayout.CENTER)
        activeStored = null
        activeTemplateTarget = null
        activeFolderDirectory = null
        activeRender = null
        replaceDetail(ERROR_CARD, panel)
        showNarrowDetail()
    }

    private fun replaceDetail(card: String, component: JComponent) {
        detailCards.removeAll()
        detailCards.add(component, card)
        detailLayout.show(detailCards, card)
        detailCards.revalidate()
        detailCards.repaint()
    }

    private fun clearSelectedTemplate() {
        loadGenerations.invalidateDetailLoad()
        libraryTree.clearSelection()
        settings.state.selectedTemplateId = null
        activeStored = null
        activeTemplateTarget = null
        activeFolderDirectory = null
        activeRender = null
        disposeUseView()
        replaceDetail(EMPTY_CARD, createEmptyState())
        showNarrowLibrary()
    }

    private fun disposeAuthor() {
        currentAuthor?.let { author ->
            authorRequests.invalidate()
            Disposer.dispose(author)
        }
        currentAuthor = null
        authorSession = null
        updateMutationAvailability()
        updateAuthorReturnVisibility()
    }

    private fun disposeUseView() {
        previewHighlights?.dispose()
        previewHighlights = null
        previewField = null
        validationLabel = null
        contextArea = null
        dynamicForm = null
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "prompt" }

    override fun dispose() {
        disposed = true
        authorRequests.invalidate()
        settings.state.splitterProportion = wideSplitter.proportion
        disposeAuthor()
        disposeUseView()
    }

    companion object {
        private const val WIDE_CARD = "wide"
        private const val NARROW_CARD = "narrow"
        private const val NARROW_LIBRARY_CARD = "narrow-library"
        private const val NARROW_DETAIL_CARD = "narrow-detail"
        private const val EMPTY_CARD = "empty"
        private const val FOLDER_CARD = "folder"
        private const val USE_CARD = "use"
        private const val AUTHOR_CARD = "author"
        private const val ERROR_CARD = "error"
    }
}

internal fun selectLibrarySelectionAfterReload(
    authorOpen: Boolean,
    currentSelection: LibrarySelectionKey?,
    activeSelection: LibrarySelectionKey?,
    persistedTemplateId: String?,
): LibrarySelectionKey? = if (authorOpen) {
    null
} else {
    currentSelection ?: activeSelection ?: persistedTemplateId?.let { LibrarySelectionKey(templateId = it) }
}

internal fun activeTemplateSelection(
    root: Path,
    activeDirectory: Path,
    templateId: String?,
): LibrarySelectionKey? = runCatching {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalizedDirectory = activeDirectory.toAbsolutePath().normalize()
    if (!normalizedDirectory.startsWith(normalizedRoot)) return@runCatching null
    LibrarySelectionKey(
        templateId = templateId,
        relativePath = portablePath(normalizedRoot.relativize(normalizedDirectory)),
    )
}.getOrNull()

internal fun hasLibraryRootChanged(previousRoot: Path, currentRoot: Path): Boolean = runCatching {
    previousRoot.toAbsolutePath().normalize() != currentRoot.toAbsolutePath().normalize()
}.getOrDefault(true)

internal fun shouldRebindLibraryWatcher(currentRoot: Path?, requestedRoot: Path): Boolean =
    currentRoot == null || hasLibraryRootChanged(currentRoot, requestedRoot)

internal fun shouldReloadSelectedDetail(
    reloadRequested: Boolean,
    authorOpen: Boolean,
    selectedDirectory: Path?,
    activeDirectory: Path?,
): Boolean = reloadRequested && !authorOpen && selectedDirectory != null && selectedDirectory == activeDirectory

internal fun shouldReloadHiddenActiveDetail(
    reloadRequested: Boolean,
    authorOpen: Boolean,
    selectedDirectory: Path?,
    activeDirectory: Path?,
): Boolean = reloadRequested && !authorOpen && selectedDirectory == null && activeDirectory != null

internal fun shouldRestartPendingDetailAfterReload(
    resolvedPendingDirectory: Path?,
    selectedTemplateDirectory: Path?,
    authorOpen: Boolean,
): Boolean = !authorOpen &&
    resolvedPendingDirectory != null &&
    resolvedPendingDirectory == selectedTemplateDirectory

internal fun resolveEditedTemplateAfterCancel(
    target: TemplateDetailTarget,
    snapshot: LibrarySnapshot,
): LibraryEntry.Template? = resolveTemplateEntry(target, flattenTemplates(snapshot.children))

internal fun readSearchIndexBody(markdownPath: Path): String {
    if (!Files.isRegularFile(markdownPath, NOFOLLOW_LINKS)) return ""
    return runCatching {
        Files.newByteChannel(markdownPath, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newReader(channel, StandardCharsets.UTF_8).readText()
        }
    }.getOrDefault("")
}

internal fun <T> runRepositoryOperationSafely(operation: () -> RepositoryResult<T>): RepositoryResult<T> = try {
    operation()
} catch (cancelled: ProcessCanceledException) {
    throw cancelled
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (exception: RuntimeException) {
    RepositoryResult.Failure(
        "Unexpected repository error: ${exception.message ?: exception.javaClass.simpleName}",
        exception,
    )
}

internal fun shouldReconcileFolderDetailAfterReload(
    templateActive: Boolean,
    authorOpen: Boolean,
    previousFolderDirectory: Path?,
    currentFolderDirectory: Path?,
): Boolean = !templateActive &&
    !authorOpen &&
    previousFolderDirectory != null &&
    currentFolderDirectory == previousFolderDirectory

internal fun authorReturnVisible(narrowMode: Boolean, authorOpen: Boolean): Boolean = narrowMode && authorOpen

internal fun afterTemplateSaved(
    savedDirectory: Path,
    showSaved: () -> Unit,
    refreshLibrary: (Path?) -> Unit,
) {
    showSaved()
    refreshLibrary(savedDirectory)
}

internal fun afterTemplateDeleted(
    clearSelection: () -> Unit,
    refreshLibrary: (Path?) -> Unit,
) {
    clearSelection()
    refreshLibrary(null)
}

internal fun shouldDiscardTemplateSummary(
    result: RepositoryResult<StoredTemplate>,
    directoryMissing: Boolean,
): Boolean = result is RepositoryResult.Failure && directoryMissing

internal enum class DetailLoadFailureAction { CLEAR_AND_RELOAD, SHOW_ERROR }

internal fun detailLoadFailureAction(
    result: RepositoryResult<StoredTemplate>,
    directoryMissing: Boolean,
): DetailLoadFailureAction = if (shouldDiscardTemplateSummary(result, directoryMissing)) {
    DetailLoadFailureAction.CLEAR_AND_RELOAD
} else {
    DetailLoadFailureAction.SHOW_ERROR
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
}

internal val USE_VIEW_PRIMARY_ACTIONS = listOf(
    UseViewAction.COPY_PROMPT,
    UseViewAction.INSERT,
    UseViewAction.EDIT,
)

internal val USE_VIEW_FILE_ACTIONS = listOf(
    UseViewAction.OPEN_MARKDOWN,
    UseViewAction.REVEAL,
    UseViewAction.COPY_PATH,
    UseViewAction.EXPORT_TEMPLATE,
    UseViewAction.EXPORT_RENDERED,
)
