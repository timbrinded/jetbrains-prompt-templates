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
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.context.PromptContextResolver
import dev.timbrinded.prompttemplates.core.ContextValue
import dev.timbrinded.prompttemplates.core.DiagnosticSeverity
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
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
import dev.timbrinded.prompttemplates.core.TemplateSearch
import dev.timbrinded.prompttemplates.core.TemplateSummary
import dev.timbrinded.prompttemplates.core.defaultVariableLabel
import dev.timbrinded.prompttemplates.destination.ActiveEditorDestination
import dev.timbrinded.prompttemplates.destination.ClipboardDestination
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class PromptTemplatesPanel(
    private val project: Project,
) : JPanel(), Disposable {
    private val settings = PromptTemplatesSettings.getInstance()
    private var repository = FileSystemPromptTemplateRepository(settings.libraryRoot)
    private val renderer = StrictPromptRenderer()
    private val parser = LinearPlaceholderParser()
    private val listModel = DefaultListModel<TemplateSummary>()
    private val templateList = JBList(listModel)
    private val searchField = SearchTextField(false)
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
    private val summaries = mutableListOf<TemplateSummary>()
    private val bodyIndex = mutableMapOf<Path, String>()
    private val sessionValues = mutableMapOf<TemplateId, MutableMap<String, String>>()
    private val loadGeneration = AtomicInteger()
    private var narrowMode = false
    private var disposed = false
    private var activeStored: StoredTemplate? = null
    private var activeContext: Map<String, ContextValue> = emptyMap()
    private var activeRender: RenderResult? = null
    private var previewField: EditorTextField? = null
    private var validationLabel: JBLabel? = null
    private var contextArea: JBTextArea? = null
    private var dynamicForm: DynamicVariableForm? = null
    private var currentAuthor: TemplateAuthorPanel? = null
    private var editingStored: StoredTemplate? = null

    init {
        layout = outerLayout
        templateList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        templateList.cellRenderer = TemplateListRenderer()
        templateList.emptyText.text = "No prompt templates yet"
        templateList.accessibleContext.accessibleName = "Prompt template library"
        templateList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) templateList.selectedValue?.let(::loadTemplate)
        }

        searchField.textEditor.accessibleContext.accessibleName = "Search prompt templates"
        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = filterTemplates()
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
        reloadLibrary()
    }

    fun focusSearch() {
        if (narrowMode) showNarrowLibrary()
        searchField.textEditor.requestFocusInWindow()
    }

    fun startNewTemplate() {
        showAuthor(
            PromptTemplateDraft(
                name = "New prompt",
                markdown = "# New prompt\n\n{{objective}}\n",
            ),
            existing = null,
        )
    }

    fun copyRenderedPrompt() = deliver(copy = true)

    fun insertRenderedPrompt() = deliver(copy = false)

    fun hasValidRenderedPrompt(): Boolean = activeRender?.isValid == true && activeStored != null

    private fun createLibraryPanel(): JPanel {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        val newButton = JButton("New")
        newButton.addActionListener { startNewTemplate() }
        val importButton = JButton("Import")
        importButton.addActionListener { importMarkdown() }
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { reloadLibrary() }
        actions.add(newButton)
        actions.add(importButton)
        actions.add(refreshButton)

        val header = JPanel(BorderLayout(JBUI.scale(6), 0))
        header.border = JBUI.Borders.empty(6)
        header.add(searchField, BorderLayout.CENTER)
        header.add(actions, BorderLayout.EAST)

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(400))
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(templateList), BorderLayout.CENTER)
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
            button.addActionListener { importMarkdown() }
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

    private fun reloadLibrary(selectDirectory: Path? = activeStored?.directory) {
        val generation = loadGeneration.incrementAndGet()
        val nextRepository = FileSystemPromptTemplateRepository(settings.libraryRoot)
        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = nextRepository.scan()
            val indexedBodies = scanned.associate { summary ->
                summary.directory to runCatching {
                    Files.readString(summary.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE))
                }.getOrDefault("")
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || generation != loadGeneration.get()) return@invokeLater
                repository = nextRepository
                summaries.clear()
                summaries += orderSummaries(scanned)
                bodyIndex.clear()
                bodyIndex.putAll(indexedBodies)
                filterTemplates(selectDirectory)
            }
        }
    }

    private fun orderSummaries(items: List<TemplateSummary>): List<TemplateSummary> {
        val pinned = settings.state.pinnedTemplateIds.withIndex().associate { it.value to it.index }
        val recent = settings.state.recentTemplateIds.withIndex().associate { it.value to it.index }
        return items.sortedWith(
            compareBy<TemplateSummary> { pinned[it.id?.value] ?: Int.MAX_VALUE }
                .thenBy { recent[it.id?.value] ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    private fun filterTemplates(selectDirectory: Path? = templateList.selectedValue?.directory) {
        val filtered = summaries.filter { TemplateSearch.matches(it, searchField.text, bodyIndex[it.directory]) }
        listModel.clear()
        filtered.forEach(listModel::addElement)
        val index = filtered.indexOfFirst { it.directory == selectDirectory }
        if (index >= 0) templateList.selectedIndex = index
    }

    private fun loadTemplate(summary: TemplateSummary) {
        val generation = loadGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = repository.load(summary.directory)
            ApplicationManager.getApplication().invokeLater {
                if (disposed || generation != loadGeneration.get()) return@invokeLater
                when (result) {
                    is RepositoryResult.Success -> showUse(result.value)
                    is RepositoryResult.Failure -> showError(summary.name, result.message)
                }
            }
        }
    }

    private fun showUse(stored: StoredTemplate) {
        disposeAuthor()
        editingStored = null
        activeStored = stored
        settings.markRecent(stored.template.id.value)
        val values = sessionValues.getOrPut(stored.template.id) { mutableMapOf() }
        activeContext = PromptContextResolver.resolve(project)

        val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
        panel.border = JBUI.Borders.empty(10)
        val header = JPanel(BorderLayout())
        val title = JBLabel(stored.template.metadata.name)
        title.font = title.font.deriveFont(title.font.style or java.awt.Font.BOLD)
        header.add(title, BorderLayout.NORTH)
        header.add(JBLabel(stored.directory.resolve("prompt.md").toString()), BorderLayout.SOUTH)
        panel.add(header, BorderLayout.NORTH)

        dynamicForm = DynamicVariableForm(stored.template.metadata.variables, values, ::refreshPreview)
        val formPanel = JPanel(BorderLayout())
        formPanel.add(JBScrollPane(dynamicForm), BorderLayout.CENTER)

        previewField = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setViewer(true)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(190))
            accessibleContext.accessibleName = "Rendered prompt preview"
        }
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

        val splitter = OnePixelSplitter(true, 0.48f)
        splitter.firstComponent = formPanel
        splitter.secondComponent = previewPanel
        panel.add(splitter, BorderLayout.CENTER)
        panel.add(createUseActions(), BorderLayout.SOUTH)

        replaceDetail(USE_CARD, panel)
        refreshPreview()
        showNarrowDetail()
    }

    private fun createUseActions(): JComponent {
        val primary = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        JButton("Copy Prompt").also { it.addActionListener { copyRenderedPrompt() }; primary.add(it) }
        JButton("Insert…").also { it.addActionListener { insertRenderedPrompt() }; primary.add(it) }
        JButton("Export Rendered…").also { it.addActionListener { exportRendered() }; primary.add(it) }
        JButton("Edit").also { it.addActionListener { editActive() }; primary.add(it) }

        val source = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        JButton("Open Markdown").also { it.addActionListener { openMarkdown() }; source.add(it) }
        JButton("Copy Path").also { it.addActionListener { copyMarkdownPath() }; source.add(it) }
        JButton("Export Template…").also { it.addActionListener { exportTemplate() }; source.add(it) }
        JButton("Reveal").also { it.addActionListener { revealSource() }; source.add(it) }
        JButton("Delete").also { it.addActionListener { deleteActive() }; source.add(it) }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(primary, BorderLayout.WEST)
            add(source, BorderLayout.EAST)
        }
    }

    private fun refreshPreview() {
        val stored = activeStored ?: return
        val values = sessionValues.getOrPut(stored.template.id) { mutableMapOf() }
        activeRender = renderer.render(stored.template, values, activeContext)
        previewField?.text = activeRender!!.renderedText
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
        )
    }

    private fun showAuthor(draft: PromptTemplateDraft, existing: StoredTemplate?) {
        disposeAuthor()
        activeStored = null
        activeRender = null
        editingStored = existing
        val author = TemplateAuthorPanel(
            project = project,
            initialDraft = draft,
            onSave = { saveDraft(it, existing) },
            onCancel = {
                if (existing != null) showUse(existing) else {
                    replaceDetail(EMPTY_CARD, createEmptyState())
                    showNarrowLibrary()
                }
            },
        )
        currentAuthor = author
        replaceDetail(AUTHOR_CARD, author)
        showNarrowDetail()
    }

    private fun saveDraft(draft: PromptTemplateDraft, existing: StoredTemplate?) {
        val repo = repository
        ApplicationManager.getApplication().executeOnPooledThread {
            if (existing != null) {
                val latest = repo.load(existing.directory)
                val externallyChanged = latest !is RepositoryResult.Success || latest.value.template != existing.template
                if (externallyChanged) {
                    var overwrite = false
                    ApplicationManager.getApplication().invokeAndWait {
                        overwrite = Messages.showYesNoDialog(
                            project,
                            "The template changed on disk after editing began. Overwrite those changes?",
                            "Prompt Template Changed",
                            "Overwrite with Draft",
                            "Cancel",
                            Messages.getWarningIcon(),
                        ) == Messages.YES
                    }
                    if (!overwrite) return@executeOnPooledThread
                }
            }
            val result = if (existing == null) repo.create(draft) else repo.update(existing.directory, draft)
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                when (result) {
                    is RepositoryResult.Success -> {
                        showUse(result.value)
                        reloadLibrary(result.value.directory)
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private fun importMarkdown() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("md")
            .withTitle("Import Prompt Template Markdown")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val markdown = runCatching { Files.readString(file.toNioPath()) }
            ApplicationManager.getApplication().invokeLater {
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
                    showAuthor(PromptTemplateDraft(name = name, variables = variables, markdown = body), null)
                }.onFailure { PromptTemplatesNotifications.error(project, "Unable to read Markdown: ${it.message}") }
            }
        }
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
        val path = activeStored?.directory?.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE) ?: return
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
            operation = { repository.delete(stored.directory) },
            successMessage = "Prompt template deleted.",
            afterSuccess = {
                activeStored = null
                activeRender = null
                replaceDetail(EMPTY_CARD, createEmptyState())
                reloadLibrary(null)
                showNarrowLibrary()
            },
        )
    }

    private fun runRepositoryOperation(
        operation: () -> RepositoryResult<*>,
        successMessage: String,
        afterSuccess: () -> Unit = {},
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = operation()
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is RepositoryResult.Success -> {
                        PromptTemplatesNotifications.info(project, successMessage)
                        afterSuccess()
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private fun showError(name: String, message: String) {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(18)
        panel.add(JBLabel("Unable to open $name"), BorderLayout.NORTH)
        panel.add(JBLabel(message), BorderLayout.CENTER)
        JButton("Refresh Library").also { button ->
            button.addActionListener { reloadLibrary() }
            panel.add(button, BorderLayout.SOUTH)
        }
        activeStored = null
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

    private fun disposeAuthor() {
        currentAuthor?.let(Disposer::dispose)
        currentAuthor = null
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "prompt" }

    override fun dispose() {
        disposed = true
        settings.state.splitterProportion = wideSplitter.proportion
        disposeAuthor()
    }

    companion object {
        private const val WIDE_CARD = "wide"
        private const val NARROW_CARD = "narrow"
        private const val NARROW_LIBRARY_CARD = "narrow-library"
        private const val NARROW_DETAIL_CARD = "narrow-detail"
        private const val EMPTY_CARD = "empty"
        private const val USE_CARD = "use"
        private const val AUTHOR_CARD = "author"
        private const val ERROR_CARD = "error"
    }
}
