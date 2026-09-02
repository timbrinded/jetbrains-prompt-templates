package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.timbrinded.prompttemplates.context.PromptContextResolver
import dev.timbrinded.prompttemplates.core.DiagnosticSeverity
import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.FolderDeletionPreview
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.LinearPlaceholderParser
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.StrictPromptRenderer
import dev.timbrinded.prompttemplates.core.TemplateDiagnostic
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSummary
import dev.timbrinded.prompttemplates.core.defaultVariableLabel
import dev.timbrinded.prompttemplates.destination.ActiveEditorDestination
import dev.timbrinded.prompttemplates.destination.ClipboardDestination
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

internal interface PromptTemplatesView {
    val searchQuery: String
    val currentSelectionKey: LibrarySelectionKey?
    val selectedLibrarySelection: LibraryTreeSelection?
    val selectedDestinationFolder: Path

    fun bindLibraryFileWatcher(root: Path)

    fun renderLibrary(
        snapshot: LibrarySnapshot,
        bodyIndex: Map<Path, String>,
        preferredSelection: LibrarySelectionKey?,
        expandedPaths: Collection<String>,
        preferPreferredSelection: Boolean,
    ): LibrarySelectionKey?

    fun clearLibrarySelection()
    fun renderDetail(detail: PromptDetailState)
    fun updateUsePreview(detail: PromptDetailState.Use)
    fun focusVariable(key: String)
    fun setInteractionState(mutationsEnabled: Boolean, authorOpen: Boolean)
    fun showNarrowDetail()
}

internal class PromptTemplatesController(
    private val project: Project,
    private val view: PromptTemplatesView,
    private val settings: PromptTemplatesSettings,
) : Disposable {
    private val state = PromptToolWindowState(settings.libraryRoot)
    private var repository = FileSystemPromptTemplateRepository(settings.libraryRoot)
    private val renderer = StrictPromptRenderer()
    private val parser = LinearPlaceholderParser()
    private val loadGenerations = LoadGenerationTracker()
    private val preferredSelections = PreferredLibrarySelectionTracker()
    private val authorRequests = AuthorAsyncRequestTracker()

    @Volatile
    private var disposed = false

    val authorOpen: Boolean
        get() = state.detail is PromptDetailState.Author

    fun start(parentDisposable: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            PromptTemplatesSettingsListener.TOPIC,
            PromptTemplatesSettingsListener(::onLibraryRootChanged),
        )
        view.bindLibraryFileWatcher(settings.libraryRoot)
        updateInteractionState()
        reloadLibrary()
    }

    fun onSearchChanged() = refreshTree()

    fun onLibraryFilesChanged() {
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed()) reloadLibrary(reloadSelectedDetail = true)
        }
    }

    fun onLibraryRootChanged(root: Path) {
        val applyChange = {
            if (!isDisposed() && hasLibraryRootChanged(state.librarySnapshot.root, root)) {
                applyLibraryRootTransition(root, clearTree = true)
                reloadLibrary()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) applyChange()
        else ApplicationManager.getApplication().invokeLater(applyChange)
    }

    private fun applyLibraryRootTransition(root: Path, clearTree: Boolean) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        view.bindLibraryFileWatcher(normalizedRoot)
        repository = FileSystemPromptTemplateRepository(normalizedRoot)
        preferredSelections.cancel()
        loadGenerations.invalidateDetailLoad()
        authorRequests.invalidate()
        settings.state.selectedTemplateId = null
        settings.state.expandedFolderPaths.clear()

        val author = state.detail as? PromptDetailState.Author
        if (author == null) {
            clearSelectedTemplate()
        } else {
            val rebased = author.author.rebasedAsNewTemplate(normalizedRoot)
            state.detail = PromptDetailState.Author(rebased)
            if (rebased != author.author) {
                PromptTemplatesNotifications.warning(
                    project,
                    "The library location changed. The open draft is unchanged and will save as a new template in the new library.",
                )
            }
            updateInteractionState()
        }

        if (clearTree) {
            state.librarySnapshot = LibrarySnapshot(normalizedRoot, emptyList())
            state.bodyIndex.clear()
            refreshTree(null)
        }
    }

    fun reloadLibrary(
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
                if (isDisposed() || !loadGenerations.isCurrentLibraryLoad(generation)) return@invokeLater
                if (hasLibraryRootChanged(state.librarySnapshot.root, scanned.root)) {
                    applyLibraryRootTransition(scanned.root, clearTree = false)
                }
                repository = nextRepository
                state.librarySnapshot = scanned
                state.bodyIndex.clear()
                state.bodyIndex.putAll(indexedBodies)

                val pendingDetail = loadGenerations.pendingDetailLoad()
                if (pendingDetail != null) loadGenerations.invalidateDetailLoad()
                val detailBeforeRefresh = state.detail
                refreshTree(preferredSelections.preferredOr(selectionAfterLibraryReload()))

                val selected = view.selectedLibrarySelection as? LibraryTreeSelection.Template
                val activeUse = detailBeforeRefresh as? PromptDetailState.Use
                val activeEntry = activeUse?.let { use -> resolveTemplateEntry(use.target, templates) }
                val pendingEntry = pendingDetail?.let { request -> resolveTemplateEntry(request.target, templates) }
                if (
                    pendingDetail != null &&
                    pendingEntry != null &&
                    shouldRestartPendingDetailAfterReload(
                        resolvedPendingDirectory = pendingEntry.directory,
                        selectedTemplateDirectory = selected?.directory,
                        authorOpen = authorOpen,
                    )
                ) {
                    startTemplateDetailLoad(pendingEntry.summary, pendingDetail.intent)
                }
                if (
                    loadGenerations.pendingDetailLoad() == null &&
                    selected != null &&
                    shouldReloadSelectedDetail(
                        reloadRequested = reloadSelectedDetail,
                        authorOpen = authorOpen,
                        selectedDirectory = selected.directory,
                        activeDirectory = activeEntry?.directory,
                    )
                ) {
                    loadTemplate(selected.entry.summary)
                } else if (
                    loadGenerations.pendingDetailLoad() == null &&
                    shouldReloadHiddenActiveDetail(
                        reloadRequested = reloadSelectedDetail,
                        authorOpen = authorOpen,
                        selectedDirectory = selected?.directory,
                        activeDirectory = activeUse?.stored?.directory,
                    ) && activeEntry != null
                ) {
                    loadTemplate(activeEntry.summary)
                }

                val differentTemplateIsLoading = selected != null && activeUse?.stored?.directory != selected.directory
                if (activeUse != null && activeEntry == null && !differentTemplateIsLoading && !authorOpen) {
                    clearSelectedTemplate()
                } else {
                    reconcileFolderDetailAfterReload(detailBeforeRefresh, folders)
                }
            }
        }
    }

    private fun reconcileFolderDetailAfterReload(
        detailBeforeRefresh: PromptDetailState,
        folders: List<LibraryEntry.Folder>,
    ) {
        val previousFolder = (detailBeforeRefresh as? PromptDetailState.Folder)?.entry?.directory ?: return
        val currentFolder = (state.detail as? PromptDetailState.Folder)?.entry?.directory
        if (authorOpen || currentFolder != previousFolder) return
        val refreshedFolder = folders.firstOrNull { it.directory == previousFolder }
        when {
            refreshedFolder == null -> clearSelectedTemplate()
            (view.selectedLibrarySelection as? LibraryTreeSelection.Folder)?.directory != previousFolder -> {
                showFolder(refreshedFolder)
            }
        }
    }

    private fun selectionAfterLibraryReload(): LibrarySelectionKey? {
        val activeSelection = (state.detail as? PromptDetailState.Use)?.let { use ->
            activeTemplateSelection(
                root = state.librarySnapshot.root,
                activeDirectory = use.stored.directory,
                templateId = use.target.templateId,
            )
        }
        return selectLibrarySelectionAfterReload(
            authorOpen = authorOpen,
            currentSelection = view.currentSelectionKey,
            activeSelection = activeSelection,
            persistedTemplateId = settings.state.selectedTemplateId,
        )
    }

    private fun refreshTree(preferredSelection: LibrarySelectionKey? = view.currentSelectionKey) {
        val pendingSelection = preferredSelections.pendingSelection()
        val actualSelection = view.renderLibrary(
            snapshot = state.librarySnapshot,
            bodyIndex = state.bodyIndex,
            preferredSelection = pendingSelection ?: preferredSelection,
            expandedPaths = settings.state.expandedFolderPaths,
            preferPreferredSelection = pendingSelection != null,
        )
        preferredSelections.acknowledge(actualSelection)
    }

    fun onLibrarySelection(selection: LibraryTreeSelection, userInitiated: Boolean) {
        if (userInitiated) preferredSelections.cancel()
        if (authorOpen) {
            view.showNarrowDetail()
            return
        }
        when (selection) {
            is LibraryTreeSelection.Template -> {
                settings.state.selectedTemplateId = selection.entry.summary.id?.value
                    ?.takeIf { selection.entry.summary.health == TemplateHealth.HEALTHY }
                val active = state.detail as? PromptDetailState.Use
                if (active?.stored?.directory != selection.directory) loadTemplate(selection.entry.summary)
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
        val repo = repository
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = repo.load(summary.directory)
            val directoryMissing = Files.notExists(summary.directory)
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed() || !loadGenerations.acceptDetailLoad(request)) return@invokeLater
                when (result) {
                    is RepositoryResult.Success -> when (intent) {
                        TemplateDetailIntent.USE -> showUse(result.value, request.target)
                        TemplateDetailIntent.EDIT -> editStored(result.value, request.target)
                    }
                    is RepositoryResult.Failure -> if (directoryMissing) {
                        clearSelectedTemplate()
                        reloadLibrary(preferredSelection = null)
                    } else {
                        showError(summary.name, result.message)
                    }
                }
            }
        }
    }

    private fun showFolder(folder: LibraryEntry.Folder) {
        loadGenerations.invalidateDetailLoad()
        showDetail(PromptDetailState.Folder(folder))
    }

    private fun showUse(
        stored: StoredTemplate,
        target: TemplateDetailTarget = TemplateDetailTarget(stored.directory, stored.template.id.value),
    ) {
        loadGenerations.invalidateDetailLoad()
        settings.markRecent(stored.template.id.value)
        val values = state.sessionValues.getOrPut(stored.template.id) { mutableMapOf() }
        val context = PromptContextResolver.resolve(project)
        val render = renderer.render(stored.template, values, context)
        val referencedContext = parser.parse(stored.template.markdown).placeholders
            .filter { it.contextReference }
            .map { it.key }
            .distinct()
        showDetail(PromptDetailState.Use(stored, target, values, context, render, referencedContext))
    }

    fun refreshPreview() {
        updatePreview(refreshContext = false)
    }

    private fun updatePreview(refreshContext: Boolean): PromptDetailState.Use? {
        val use = state.detail as? PromptDetailState.Use ?: return null
        val context = if (refreshContext) PromptContextResolver.resolve(project) else use.context
        val updated = use.copy(
            context = context,
            render = renderer.render(use.stored.template, use.values, context),
        )
        state.detail = updated
        view.updateUsePreview(updated)
        return updated
    }

    fun performUseViewAction(action: UseViewAction) {
        when (action) {
            UseViewAction.COPY_PROMPT -> deliver(copy = true)
            UseViewAction.INSERT -> deliver(copy = false)
            UseViewAction.EDIT -> editActive()
            UseViewAction.OPEN_MARKDOWN -> openMarkdown()
            UseViewAction.REVEAL -> revealSource()
            UseViewAction.COPY_PATH -> copyMarkdownPath()
            UseViewAction.EXPORT_TEMPLATE -> exportTemplate()
            UseViewAction.EXPORT_RENDERED -> exportRendered()
            UseViewAction.DELETE -> deleteActive()
        }
    }

    fun hasValidRenderedPrompt(): Boolean = (state.detail as? PromptDetailState.Use)?.render?.isValid == true

    private fun deliver(copy: Boolean) {
        val use = updatePreview(refreshContext = true) ?: return
        val render = use.render
        if (!render.isValid) {
            val error = render.diagnostics.firstOrNull { it.severity == DiagnosticSeverity.ERROR }
            if (error is TemplateDiagnostic.MissingRequiredValue) view.focusVariable(error.key)
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
        settings.markRecent(use.stored.template.id.value)
    }

    fun startNewTemplate() = startNewTemplateAt(view.selectedDestinationFolder)

    fun startNewTemplateAt(destination: Path) {
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

    private fun editActive() {
        if (!canChangeLibrary()) return
        val use = state.detail as? PromptDetailState.Use ?: return
        showAuthor(draftOf(use.stored), use.stored, use.stored.directory.parent, use.target)
    }

    private fun showAuthor(
        draft: PromptTemplateDraft,
        existing: StoredTemplate?,
        destination: Path,
        existingTarget: TemplateDetailTarget? = null,
    ) {
        authorRequests.invalidate()
        loadGenerations.invalidateDetailLoad()
        val author = TemplateAuthorState(
            draft = draft,
            existing = existing,
            existingTarget = existingTarget ?: existing?.let {
                TemplateDetailTarget(it.directory, it.template.id.value)
            },
            selectionBefore = view.currentSelectionKey,
            destination = destination,
        )
        showDetail(PromptDetailState.Author(author))
    }

    fun cancelAuthor() {
        val author = (state.detail as? PromptDetailState.Author)?.author ?: return
        authorRequests.invalidate()
        showDetail(PromptDetailState.Empty)
        val existing = author.existing
        val target = author.existingTarget
        if (existing != null && target != null) {
            val latest = resolveTemplateEntry(target, flattenTemplates(state.librarySnapshot.children))
            if (latest == null) {
                clearSelectedTemplate()
                return
            }
            val relativePath = portableRelativePath(state.librarySnapshot.root, latest.directory)
            val templateId = latest.summary.id?.value ?: target.templateId
            val selection = if (templateId == null) {
                LibrarySelectionKey.TemplatePath(relativePath)
            } else {
                LibrarySelectionKey.Template(templateId, relativePath)
            }
            refreshTree(selection)
            if ((view.selectedLibrarySelection as? LibraryTreeSelection.Template)?.directory != latest.directory) {
                loadTemplate(latest.summary)
            }
            return
        }

        author.selectionBefore?.let(::refreshTree) ?: clearSelectedTemplate()
    }

    fun saveDraft(draft: PromptTemplateDraft) {
        val author = (state.detail as? PromptDetailState.Author)?.author ?: return
        val existing = author.existing
        val request = authorRequests.beginSave(author.destination) ?: return
        val repo = repository
        val libraryRootAtRequest = settings.libraryRoot
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!authorRequests.isCurrent(request)) return@executeOnPooledThread
            if (existing != null) {
                val latest = repo.load(existing.directory)
                val externallyChanged = latest !is RepositoryResult.Success || latest.value.template != existing.template
                if (externallyChanged && !confirmOverwrite(request)) {
                    authorRequests.finishSave(request)
                    return@executeOnPooledThread
                }
            }
            if (!authorRequests.isCurrent(request)) return@executeOnPooledThread
            val result = if (existing == null) {
                repo.create(draft, request.destination)
            } else {
                repo.update(existing.directory, draft)
            }
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed() || !authorRequests.isCurrent(request)) return@invokeLater
                if (hasLibraryRootChanged(libraryRootAtRequest, settings.libraryRoot)) {
                    authorRequests.invalidate()
                    return@invokeLater
                }
                authorRequests.finishSave(request)
                when (result) {
                    is RepositoryResult.Success -> {
                        showWarnings(result.warnings)
                        showUse(result.value)
                        reloadLibrary(
                            LibrarySelectionKey.Template(
                                result.value.template.id.value,
                                portableRelativePath(libraryRootAtRequest, result.value.directory),
                            ),
                        )
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private fun confirmOverwrite(request: AuthorAsyncRequest): Boolean {
        if (isDisposed() || !authorRequests.isCurrent(request)) return false
        var overwrite = false
        ApplicationManager.getApplication().invokeAndWait {
            if (isDisposed() || !authorRequests.isCurrent(request)) return@invokeAndWait
            overwrite = Messages.showYesNoDialog(
                project,
                "The template changed on disk after editing began. Overwrite those changes?",
                "Prompt Template Changed",
                "Overwrite with Draft",
                "Cancel",
                Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return overwrite
    }

    fun importMarkdown(destination: Path = view.selectedDestinationFolder) {
        if (!canChangeLibrary()) return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("md")
            .withTitle("Import Prompt Template Markdown")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val request = authorRequests.begin(destination)
        ApplicationManager.getApplication().executeOnPooledThread {
            val markdown = runCatching { Files.readString(file.toNioPath()) }
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed() || !authorRequests.isCurrent(request)) return@invokeLater
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

    fun performLibraryCommand(command: LibraryTreeCommand, target: LibraryTreeSelection) {
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
            LibraryTreeCommand.MOVE_UP -> if (target !is LibraryTreeSelection.Root) {
                moveSibling(target, MoveDirection.UP)
            }
            LibraryTreeCommand.MOVE_DOWN -> if (target !is LibraryTreeSelection.Root) {
                moveSibling(target, MoveDirection.DOWN)
            }
            LibraryTreeCommand.OPEN_MARKDOWN -> (target as? LibraryTreeSelection.Template)?.let {
                openMarkdown(it.directory)
            }
            LibraryTreeCommand.DELETE_FOLDER -> (target as? LibraryTreeSelection.Folder)?.let(::deleteFolder)
            LibraryTreeCommand.DELETE_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let(::deleteTemplate)
            LibraryTreeCommand.EXPAND_ALL,
            LibraryTreeCommand.COLLAPSE_ALL,
            LibraryTreeCommand.EXPAND_BRANCH,
            LibraryTreeCommand.COLLAPSE_BRANCH,
            -> Unit
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
                reloadLibrary(LibrarySelectionKey.Folder(portableRelativePath(settings.libraryRoot, directory)))
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
                reloadLibrary(LibrarySelectionKey.Folder(newRelative))
            },
        )
    }

    private fun moveToFolder(source: LibraryTreeSelection) {
        if (!canChangeLibrary()) return
        val folders = buildList {
            add(state.librarySnapshot.root)
            addAll(flattenFolders(state.librarySnapshot.children).map(LibraryEntry.Folder::directory))
        }.filterNot { candidate ->
            source is LibraryTreeSelection.Folder &&
                (candidate == source.directory || candidate.startsWith(source.directory))
        }
        if (folders.isEmpty()) return
        val options = folders.map { directory ->
            if (directory == state.librarySnapshot.root) "/ (Library root)"
            else portableRelativePath(state.librarySnapshot.root, directory)
        }.toTypedArray()
        val currentParent = source.directory.parent
        val initialIndex = folders.indexOf(currentParent).takeIf { it >= 0 } ?: 0
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(options.toList())
            .setTitle("Move Library Entry")
            .setSelectedValue(options[initialIndex], true)
            .setItemChosenCallback { choice ->
                val destination = folders[options.indexOf(choice)]
                if (source.directory.parent != destination) {
                    moveEntry(source, destination, EntryPlacement.EndOfKind)
                }
            }
            .createPopup()
            .showInFocusCenter()
    }

    private fun moveSibling(source: LibraryTreeSelection, direction: MoveDirection) {
        if (!canChangeLibrary()) return
        val move = siblingMove(state.librarySnapshot, source, direction) ?: return
        moveEntry(source, move.destination, move.placement)
    }

    fun moveEntry(source: LibraryTreeSelection, destination: Path, placement: EntryPlacement) {
        if (!canChangeLibrary()) return
        val keyBeforeMove = selectionKey(source, state.librarySnapshot.root)
        val oldRelative = portableRelativePath(state.librarySnapshot.root, source.directory)
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
                val preferred = when (keyBeforeMove) {
                    is LibrarySelectionKey.Folder -> LibrarySelectionKey.Folder(newRelative)
                    is LibrarySelectionKey.Template -> keyBeforeMove.copy(relativePath = newRelative)
                    is LibrarySelectionKey.TemplatePath -> LibrarySelectionKey.TemplatePath(newRelative)
                    null -> null
                }
                reloadLibrary(preferred)
            },
        )
    }

    private fun editTemplate(target: LibraryTreeSelection.Template) {
        if (canChangeLibrary()) startTemplateDetailLoad(target.entry.summary, TemplateDetailIntent.EDIT)
    }

    private fun editStored(stored: StoredTemplate, target: TemplateDetailTarget) {
        showAuthor(draftOf(stored), stored, stored.directory.parent, target)
    }

    private fun draftOf(stored: StoredTemplate) = PromptTemplateDraft(
        id = stored.template.id,
        name = stored.template.metadata.name,
        description = stored.template.metadata.description,
        tags = stored.template.metadata.tags,
        variables = stored.template.metadata.variables,
        markdown = stored.template.markdown,
    )

    private fun deleteTemplate(target: LibraryTreeSelection.Template) {
        deleteTemplate(target.entry.summary.name, target.directory)
    }

    private fun deleteActive() {
        val use = state.detail as? PromptDetailState.Use ?: return
        deleteTemplate(use.stored.template.metadata.name, use.stored.directory)
    }

    private fun deleteTemplate(name: String, directory: Path) {
        if (!canChangeLibrary()) return
        if (settings.state.confirmDeletion) {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete '$name' and its source files?",
                "Delete Prompt Template",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        runRepositoryOperation(
            operation = { repository.deleteTemplate(directory) },
            successMessage = "Prompt template deleted.",
            afterSuccess = {
                clearSelectedTemplate()
                reloadLibrary(null)
            },
        )
    }

    private fun deleteFolder(target: LibraryTreeSelection.Folder) {
        if (!canChangeLibrary()) return
        state.mutationInProgress = true
        updateInteractionState()
        ApplicationManager.getApplication().executeOnPooledThread {
            val previewResult = repository.previewFolderDeletion(target.directory)
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed()) return@invokeLater
                state.mutationInProgress = false
                updateInteractionState()
                when (previewResult) {
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, previewResult.message)
                    is RepositoryResult.Success -> confirmFolderDeletion(target, previewResult.value)
                }
            }
        }
    }

    private fun confirmFolderDeletion(target: LibraryTreeSelection.Folder, preview: FolderDeletionPreview) {
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
                val deletedPath = portableRelativePath(settings.libraryRoot, target.directory)
                settings.state.expandedFolderPaths.removeIf { path ->
                    path == deletedPath || path.startsWith("$deletedPath/")
                }
                clearSelectedTemplate()
                reloadLibrary(null)
            },
        )
    }

    private fun revealSelection(target: LibraryTreeSelection) {
        com.intellij.ide.actions.RevealFileAction.openFile(sourcePath(target).toFile())
    }

    private fun copySelectionPath(target: LibraryTreeSelection) {
        CopyPasteManager.getInstance().setContents(StringSelection(sourcePath(target).toString()))
        PromptTemplatesNotifications.info(project, "Path copied.")
    }

    private fun sourcePath(target: LibraryTreeSelection): Path = when (target) {
        is LibraryTreeSelection.Template -> target.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        else -> target.directory
    }

    private fun exportTemplate() {
        val use = state.detail as? PromptDetailState.Use ?: return
        val destination = chooseDestination(slug(use.stored.template.metadata.name) + ".md") ?: return
        runRepositoryOperation(
            operation = { repository.exportTemplateMarkdown(use.stored.directory, destination) },
            successMessage = "Template Markdown exported to $destination.",
        )
    }

    private fun exportRendered() {
        val use = updatePreview(refreshContext = true) ?: return
        if (!use.render.isValid) {
            PromptTemplatesNotifications.error(project, "Complete required values before exporting.")
            return
        }
        val destination = chooseDestination(slug(use.stored.template.metadata.name) + "-rendered.md") ?: return
        runRepositoryOperation(
            operation = { repository.exportRenderedMarkdown(use.render.renderedText, destination) },
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
        val use = state.detail as? PromptDetailState.Use ?: return
        openMarkdown(use.stored.directory)
    }

    private fun openMarkdown(directory: Path) {
        val path = directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (file == null) PromptTemplatesNotifications.error(project, "Unable to find $path.")
        else FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun revealSource() {
        val use = state.detail as? PromptDetailState.Use ?: return
        val path = use.stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        com.intellij.ide.actions.RevealFileAction.openFile(path.toFile())
    }

    private fun copyMarkdownPath() {
        val use = state.detail as? PromptDetailState.Use ?: return
        val path = use.stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
        PromptTemplatesNotifications.info(project, "Markdown path copied.")
    }

    private fun <T> runRepositoryOperation(
        operation: () -> RepositoryResult<T>,
        successMessage: String,
        afterSuccess: (T) -> Unit = {},
    ) {
        if (state.mutationInProgress) return
        state.mutationInProgress = true
        updateInteractionState()
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
                if (isDisposed()) return@invokeLater
                state.mutationInProgress = false
                updateInteractionState()
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
            if (!isDisposed()) {
                state.mutationInProgress = false
                updateInteractionState()
            }
        }
    }

    private fun showWarnings(warnings: List<String>) {
        warnings.forEach { PromptTemplatesNotifications.warning(project, it) }
    }

    private fun updateInteractionState() {
        view.setInteractionState(
            mutationsEnabled = !state.mutationInProgress && !authorOpen,
            authorOpen = authorOpen,
        )
    }

    private fun canChangeLibrary(): Boolean {
        if (state.mutationInProgress) return false
        if (!authorOpen) return true
        PromptTemplatesNotifications.error(project, "Save or cancel the open template before changing the library.")
        return false
    }

    private fun showError(name: String, message: String) {
        loadGenerations.invalidateDetailLoad()
        showDetail(PromptDetailState.LoadError(name, message))
    }

    private fun clearSelectedTemplate() {
        loadGenerations.invalidateDetailLoad()
        view.clearLibrarySelection()
        settings.state.selectedTemplateId = null
        showDetail(PromptDetailState.Empty)
    }

    private fun showDetail(detail: PromptDetailState) {
        state.detail = detail
        view.renderDetail(detail)
        updateInteractionState()
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "prompt" }

    private fun isDisposed(): Boolean = disposed || project.isDisposed

    override fun dispose() {
        disposed = true
        authorRequests.invalidate()
        loadGenerations.invalidateDetailLoad()
    }
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
