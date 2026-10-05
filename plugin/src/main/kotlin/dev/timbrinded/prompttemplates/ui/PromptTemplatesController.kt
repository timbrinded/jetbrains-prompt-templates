package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.components.service
import dev.timbrinded.prompttemplates.LibraryChange
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
import dev.timbrinded.prompttemplates.core.DiagnosticSeverity
import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateDiagnostic
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSummary
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import dev.timbrinded.prompttemplates.settings.PromptTemplatesWorkspaceState
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface PromptTemplatesView {
    val selectedDestinationFolder: Path

    fun renderLibrary(
        snapshot: LibrarySnapshot,
        bodyIndex: Map<Path, String>,
        selectedKey: LibrarySelectionKey?,
        expandedPaths: Collection<String>,
        loading: Boolean,
    )

    fun clearLibrarySelection()
    fun revertLibrarySelection(selectedKey: LibrarySelectionKey?)
    fun renderDetail(detail: PromptDetailState)
    fun updateUsePreview(detail: PromptDetailState.Use)
    fun focusVariable(key: String)
    fun setInteractionState(mutationsEnabled: Boolean, authorOpen: Boolean)
    fun showNarrowDetail()
    fun confirmDiscardAuthor(): Boolean
}

private data class LibraryReload(
    val snapshot: LibrarySnapshot,
    val templates: List<LibraryEntry.Template>,
    val indexedBodies: Map<Path, String>,
)

/**
 * The tool window's state: the library, the selection, the detail it shows and transitions between libraries.
 * File changes are delegated to [LibraryMutations] and drafts to [AuthorFlow].
 */
internal class PromptTemplatesController(
    private val project: Project,
    private val view: PromptTemplatesView,
    private val settings: PromptTemplatesSettings,
    private val workspace: PromptTemplatesWorkspaceState,
    private val coroutineScope: CoroutineScope,
) : Disposable, LibraryMutationHost, AuthorFlowHost {
    private val state = PromptToolWindowState(settings.libraryRoot)
    override var repository = FileSystemPromptTemplateRepository(settings.libraryRoot)
        private set
    private val projectService = project.service<PromptTemplatesProjectService>()
    private val invocation = projectService.invocation
    private var showingInvocation = invocation.state.value != null
    private val loadGenerations = LoadGenerationTracker()
    private val mutations = LibraryMutations(project, this, settings, workspace, projectService, coroutineScope)
    private val authorFlow = AuthorFlow(project, this, view, settings, projectService, coroutineScope)
    override var selectedKey: LibrarySelectionKey? =
        workspace.selectedTemplateId?.let(LibrarySelectionKey::Template)
        private set

    @Volatile
    private var disposed = false

    val authorOpen: Boolean
        get() = state.detail is PromptDetailState.Author

    override val librarySnapshot: LibrarySnapshot
        get() = state.librarySnapshot

    override val openAuthor: TemplateAuthorState?
        get() = (state.detail as? PromptDetailState.Author)?.author

    fun start(parentDisposable: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            PromptTemplatesSettingsListener.TOPIC,
            PromptTemplatesSettingsListener(::onLibraryRootChanged),
        )
        coroutineScope.launch(Dispatchers.EDT) {
            // This view reloads after its own writes itself, with the selection that write calls for.
            projectService.libraryChanges.collect { change -> if (change == LibraryChange.EXTERNAL) onLibraryFilesChanged() }
        }
        coroutineScope.launch(Dispatchers.EDT) {
            invocation.state.collect { session ->
                if (!showingInvocation || session == null) return@collect
                val detail = PromptDetailState.Use(session)
                val previous = state.detail as? PromptDetailState.Use
                // Another view, such as Quick Use, opened a different template; select it unless it already is.
                val key = templateKey(detail.stored)
                if (previous?.stored?.template?.id != detail.stored.template.id && !isSameLibrarySelection(selectedKey, key)) {
                    selectedKey = key
                    workspace.selectedTemplateId = key.templateId
                    refreshTree()
                }
                state.detail = detail
                if (previous?.stored == detail.stored) view.updateUsePreview(detail)
                else view.renderDetail(detail)
                updateInteractionState()
            }
        }
        updateInteractionState()
        // Give the tree its real root before the first scan lands, so New Template and Import started in
        // that window target the library instead of the tree's placeholder root.
        refreshTree()
        reloadLibrary()
    }

    fun onSearchChanged() = refreshTree()

    fun onLibraryFilesChanged() {
        if (!isDisposed()) reloadLibrary(reloadSelectedDetail = true)
    }

    fun onLibraryRootChanged(root: Path) {
        val applyChange = {
            if (!isDisposed() && hasLibraryRootChanged(state.librarySnapshot.root, root)) {
                applyLibraryRootTransition(root, clearTree = true)
                reloadLibrary()
            }
        }
        UIUtil.invokeLaterIfNeeded(applyChange)
    }

    private fun applyLibraryRootTransition(root: Path, clearTree: Boolean) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        repository = FileSystemPromptTemplateRepository(normalizedRoot)
        loadGenerations.invalidateDetailLoad()
        // A save that is mid-flight reports its own outcome when it lands, so its draft needs no rebase warning.
        val saveInFlight = authorFlow.saveInProgress
        authorFlow.invalidateRequests()
        selectedKey = null
        workspace.selectedTemplateId = null
        workspace.replaceExpandedFolderPaths(emptyList())

        val author = state.detail as? PromptDetailState.Author
        if (author == null) {
            clearSelectedTemplate()
        } else {
            val rebased = author.author.rebasedAsNewTemplate(normalizedRoot)
            state.detail = PromptDetailState.Author(rebased)
            if (rebased != author.author && !saveInFlight) {
                PromptTemplatesNotifications.warning(
                    project,
                    "The library location changed. The open draft is unchanged and will save as a new template in the new library.",
                )
            }
            updateInteractionState()
        }

        if (clearTree) {
            state.librarySnapshot = LibrarySnapshot(normalizedRoot, emptyList())
            state.libraryLoaded = false
            state.bodyIndex.clear()
            refreshTree()
        }
    }

    fun reloadLibrary(
        selection: LibrarySelectionKey? = selectedKey,
        reloadSelectedDetail: Boolean = false,
    ) {
        selectedKey = selection
        val nextRepository = FileSystemPromptTemplateRepository(settings.libraryRoot)
        val generation = loadGenerations.beginLibraryLoad(nextRepository.root)
        coroutineScope.launch {
            val (scanned, templates, indexedBodies) = withContext(Dispatchers.IO) {
                val snapshot = nextRepository.scan()
                val loadedTemplates = flattenTemplates(snapshot.children)
                LibraryReload(
                    snapshot = snapshot,
                    templates = loadedTemplates,
                    indexedBodies = loadedTemplates.associate { entry ->
                        val markdownPath = entry.summary.directory
                            .resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
                        entry.summary.directory to readSearchIndexBody(markdownPath)
                    },
                )
            }
            withContext(Dispatchers.EDT) {
                if (isDisposed() || !loadGenerations.acceptLibraryLoad(generation, scanned.root)) return@withContext
                if (scanned.locked && state.libraryLoaded && !hasLibraryRootChanged(state.librarySnapshot.root, scanned.root)) {
                    // Another IDE held the lock, so this scan describes nothing: keep the current tree and selection.
                    PromptTemplatesNotifications.warning(project, scanned.diagnostic ?: "The template library is busy.")
                    return@withContext
                }
                if (hasLibraryRootChanged(state.librarySnapshot.root, scanned.root)) {
                    applyLibraryRootTransition(scanned.root, clearTree = false)
                }
                repository = nextRepository
                state.librarySnapshot = scanned
                state.libraryLoaded = true
                state.bodyIndex.clear()
                state.bodyIndex.putAll(indexedBodies)

                val pendingDetail = loadGenerations.pendingDetailLoad()
                if (pendingDetail != null) loadGenerations.invalidateDetailLoad()
                val selected = resolveLibrarySelection(scanned, selectedKey)
                adoptSelection(selected)
                refreshTree()
                reconcileDetailAfterReload(selected, pendingDetail, templates, reloadSelectedDetail)
            }
        }
    }

    override fun reloadAfterMutation(preferred: LibrarySelectionKey?, keyAtStart: LibrarySelectionKey?) {
        if (!isSameLibrarySelection(selectedKey, keyAtStart)) {
            reloadLibrary()
            return
        }
        if (preferred == null) clearSelectedTemplate()
        reloadLibrary(preferred)
    }

    private fun reconcileDetailAfterReload(
        selected: LibraryTreeSelection?,
        pendingDetail: TemplateDetailRequest?,
        templates: List<LibraryEntry.Template>,
        reloadSelectedDetail: Boolean,
    ) {
        if (authorOpen) return
        val active = state.detail as? PromptDetailState.Use
        // The project service already re-checked the open invocation when it reported the change.
        if (active != null && pendingDetail == null && (
                reloadSelectedDetail ||
                    selected is LibraryTreeSelection.Template && selected.entry.summary.id == active.stored.template.id
                )) {
            return
        }
        when (selected) {
            is LibraryTreeSelection.Template -> {
                val pendingEntry = pendingDetail?.let { request -> resolveTemplateEntry(request.target, templates) }
                if (pendingDetail != null && pendingEntry?.directory == selected.directory) {
                    startTemplateDetailLoad(pendingEntry.summary, pendingDetail.intent)
                    return
                }
                val active = state.detail as? PromptDetailState.Use
                // A file change may refresh this view's own template, but must not take over an invocation
                // that Quick Use is showing while this view shows a folder, an empty state or an error.
                if (reloadSelectedDetail && active == null && invocation.state.value != null) return
                if (reloadSelectedDetail || active?.stored?.directory != selected.directory) {
                    loadTemplate(selected.entry.summary)
                }
            }
            is LibraryTreeSelection.Folder -> showFolder(selected.entry)
            is LibraryTreeSelection.Root, null -> clearSelectedTemplate()
        }
    }

    private fun refreshTree() {
        view.renderLibrary(
            snapshot = state.librarySnapshot,
            bodyIndex = state.bodyIndex,
            selectedKey = selectedKey,
            expandedPaths = workspace.expandedFolderPaths,
            loading = !state.libraryLoaded,
        )
    }

    private fun adoptSelection(selection: LibraryTreeSelection?) {
        selectedKey = selectionKey(selection, state.librarySnapshot.root)
        workspace.selectedTemplateId = (selection as? LibraryTreeSelection.Template)
            ?.entry
            ?.summary
            ?.takeIf { summary -> summary.health == TemplateHealth.HEALTHY }
            ?.id
            ?.value
    }

    fun onLibrarySelection(selection: LibraryTreeSelection) {
        if (authorOpen) {
            // The draft stays open, so the tree must keep showing the entry it belongs to.
            view.revertLibrarySelection(selectedKey)
            view.showNarrowDetail()
            return
        }
        adoptSelection(selection)
        when (selection) {
            is LibraryTreeSelection.Template -> {
                val active = state.detail as? PromptDetailState.Use
                if (active == null || active.stored.template.id != selection.entry.summary.id) loadTemplate(selection.entry.summary)
            }
            is LibraryTreeSelection.Folder -> {
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
        coroutineScope.launch {
            val (result, directoryMissing) = withContext(Dispatchers.IO) {
                repo.load(summary.directory) to Files.notExists(summary.directory)
            }
            withContext(Dispatchers.EDT) {
                if (isDisposed() || !loadGenerations.acceptDetailLoad(request)) return@withContext
                when (result) {
                    is RepositoryResult.Success -> when (intent) {
                        TemplateDetailIntent.USE -> showUse(result.value)
                        TemplateDetailIntent.EDIT -> authorFlow.edit(result.value)
                        TemplateDetailIntent.DUPLICATE -> authorFlow.duplicate(result.value)
                    }
                    is RepositoryResult.Failure -> if (directoryMissing) {
                        clearSelectedTemplate()
                        reloadLibrary(selection = null)
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

    private fun showUse(stored: StoredTemplate) {
        loadGenerations.invalidateDetailLoad()
        showingInvocation = true
        invocation.open(stored)
    }

    fun continueInvocation(): Boolean {
        if (authorOpen) return false
        val current = invocation.state.value ?: return false
        loadGenerations.invalidateDetailLoad()
        showingInvocation = true
        val key = templateKey(current.invocation.stored)
        selectedKey = key
        workspace.selectedTemplateId = key.templateId
        state.detail = PromptDetailState.Use(current)
        view.renderDetail(state.detail)
        refreshTree()
        updateInteractionState()
        return true
    }

    fun setInvocationValue(key: String, value: String) = invocation.setValue(key, value)

    fun performUseViewAction(action: UseViewAction) {
        val use = state.detail as? PromptDetailState.Use
        when (action) {
            UseViewAction.COPY_PROMPT -> deliver(copy = true)
            UseViewAction.INSERT -> deliver(copy = false)
            UseViewAction.EDIT -> if (canChangeLibrary() && use != null) authorFlow.edit(use.stored)
            UseViewAction.DUPLICATE -> use?.let { authorFlow.duplicate(it.stored) }
            UseViewAction.OPEN_MARKDOWN -> use?.let { openMarkdown(it.stored.directory) }
            UseViewAction.REVEAL -> use?.let { revealSource(it.stored) }
            UseViewAction.COPY_PATH -> use?.let { copyMarkdownPath(it.stored) }
            UseViewAction.EXPORT_TEMPLATE -> use?.let { mutations.exportTemplate(it.stored) }
            UseViewAction.EXPORT_RENDERED -> use?.let(::exportRendered)
            UseViewAction.OPEN_RENDERED_SCRATCH -> projectService.openRenderedScratch()
            UseViewAction.DELETE -> use?.let { mutations.deleteTemplate(it.stored.template.metadata.name, it.stored.directory, it.stored.template.id) }
            UseViewAction.ADD_CONTEXT -> projectService.manageAttachments()
            UseViewAction.REFRESH_CONTEXT -> invocation.refreshContext()
            UseViewAction.RELOAD_TEMPLATE -> invocation.checkTemplate(reload = true)
            UseViewAction.SELECT_INSERTION_TARGET -> invocation.selectInsertionTarget()
            // The session update refreshes the form in place.
            UseViewAction.RESET_VALUES -> invocation.resetValues()
        }
    }

    private fun deliver(copy: Boolean) {
        val result = if (copy) projectService.copyRendered() else projectService.insertRendered()
        if (result is DestinationResult.Failure) {
            val error = invocation.state.value?.invocation?.render?.diagnostics
                ?.firstOrNull { it.severity == DiagnosticSeverity.ERROR }
            if (error is TemplateDiagnostic.MissingRequiredValue) view.focusVariable(error.key)
        }
    }

    private fun exportRendered(use: PromptDetailState.Use) {
        val payload = invocation.renderedPayload()
        if (payload == null) {
            PromptTemplatesNotifications.error(project, invocation.state.value?.deliveryProblem ?: "Choose a template first.")
            return
        }
        mutations.exportRendered(use.stored, payload)
    }

    private fun templateKey(stored: StoredTemplate) = LibrarySelectionKey.Template(
        stored.template.id.value,
        portableRelativePath(state.librarySnapshot.root, stored.directory),
    )

    fun startNewTemplate() = authorFlow.startNewTemplate()

    fun startNewTemplateAt(destination: Path) = authorFlow.startNewTemplateAt(destination)

    fun browseExamples() = authorFlow.browseExamples()

    fun startTemplateFromSelection(text: String, sourceName: String) = authorFlow.startTemplateFromSelection(text, sourceName)

    fun importMarkdown(destination: Path = view.selectedDestinationFolder) = authorFlow.importMarkdown(destination)

    fun saveDraft(draft: PromptTemplateDraft) = authorFlow.saveDraft(draft)

    fun cancelAuthor() {
        if (state.detail !is PromptDetailState.Author) return
        if (!view.confirmDiscardAuthor()) return
        authorFlow.invalidateRequests()
        showDetail(PromptDetailState.Empty)
        val selected = resolveLibrarySelection(state.librarySnapshot, selectedKey)
        adoptSelection(selected)
        refreshTree()
        when (selected) {
            is LibraryTreeSelection.Template -> loadTemplate(selected.entry.summary)
            is LibraryTreeSelection.Folder -> showFolder(selected.entry)
            is LibraryTreeSelection.Root, null -> clearSelectedTemplate()
        }
    }

    override fun showAuthor(author: TemplateAuthorState) {
        loadGenerations.invalidateDetailLoad()
        showDetail(PromptDetailState.Author(author))
    }

    override fun showSavedTemplate(saved: StoredTemplate, selection: LibrarySelectionKey) {
        showUse(saved)
        reloadLibrary(selection)
    }

    override fun closeAuthor() = clearSelectedTemplate()

    override fun reloadLibraryAndDetail() = reloadLibrary(reloadSelectedDetail = true)

    fun performLibraryCommand(command: LibraryTreeCommand, target: LibraryTreeSelection) {
        when (command) {
            LibraryTreeCommand.NEW_TEMPLATE -> authorFlow.startNewTemplateAt(destinationFor(target))
            LibraryTreeCommand.NEW_FOLDER -> mutations.createFolder(destinationFor(target))
            LibraryTreeCommand.RENAME_FOLDER -> (target as? LibraryTreeSelection.Folder)?.let(mutations::renameFolder)
            LibraryTreeCommand.EDIT_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let {
                if (canChangeLibrary()) startTemplateDetailLoad(it.entry.summary, TemplateDetailIntent.EDIT)
            }
            LibraryTreeCommand.DUPLICATE_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let {
                if (canChangeLibrary()) startTemplateDetailLoad(it.entry.summary, TemplateDetailIntent.DUPLICATE)
            }
            LibraryTreeCommand.MOVE_TO_FOLDER -> if (target !is LibraryTreeSelection.Root) mutations.moveToFolder(target)
            LibraryTreeCommand.MOVE_UP -> if (target !is LibraryTreeSelection.Root) {
                mutations.moveSibling(target, MoveDirection.UP)
            }
            LibraryTreeCommand.MOVE_DOWN -> if (target !is LibraryTreeSelection.Root) {
                mutations.moveSibling(target, MoveDirection.DOWN)
            }
            LibraryTreeCommand.OPEN_MARKDOWN -> (target as? LibraryTreeSelection.Template)?.let {
                openMarkdown(it.directory)
            }
            LibraryTreeCommand.DELETE_FOLDER -> (target as? LibraryTreeSelection.Folder)?.let(mutations::deleteFolder)
            LibraryTreeCommand.DELETE_TEMPLATE -> (target as? LibraryTreeSelection.Template)?.let {
                mutations.deleteTemplate(it.entry.summary.name, it.directory, it.entry.summary.id)
            }
            LibraryTreeCommand.EXPAND_ALL,
            LibraryTreeCommand.COLLAPSE_ALL,
            -> Unit
        }
    }

    fun moveEntry(source: LibraryTreeSelection, destination: Path, placement: EntryPlacement) =
        mutations.moveEntry(source, destination, placement)

    private fun destinationFor(target: LibraryTreeSelection): Path = when (target) {
        is LibraryTreeSelection.Root -> target.directory
        is LibraryTreeSelection.Folder -> target.directory
        is LibraryTreeSelection.Template -> target.directory.parent
    }

    private fun openMarkdown(directory: Path) {
        val path = directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        coroutineScope.launch {
            // Refreshing the file from disk can block on a slow mount, so resolve it off the EDT.
            val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }
            withContext(Dispatchers.EDT) {
                if (isDisposed()) return@withContext
                if (file == null) PromptTemplatesNotifications.error(project, "Unable to find $path.")
                else FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    }

    private fun revealSource(stored: StoredTemplate) {
        val path = stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        com.intellij.ide.actions.RevealFileAction.openFile(path.toFile())
    }

    private fun copyMarkdownPath(stored: StoredTemplate) {
        val path = stored.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
        PromptTemplatesNotifications.info(project, "Markdown path copied.")
    }

    override fun mutationStateChanged() = updateInteractionState()

    private fun updateInteractionState() {
        view.setInteractionState(
            mutationsEnabled = !mutations.inProgress && !authorOpen,
            authorOpen = authorOpen,
        )
    }

    override fun canChangeLibrary(): Boolean {
        if (mutations.inProgress) return false
        if (!authorOpen) return true
        PromptTemplatesNotifications.error(project, "Save or cancel the open template before changing the library.")
        return false
    }

    override fun isCurrentLibraryRoot(root: Path): Boolean = loadGenerations.isCurrentLibraryRoot(root)

    private fun showError(name: String, message: String) {
        loadGenerations.invalidateDetailLoad()
        showDetail(PromptDetailState.LoadError(name, message))
    }

    private fun clearSelectedTemplate() {
        loadGenerations.invalidateDetailLoad()
        selectedKey = null
        view.clearLibrarySelection()
        workspace.selectedTemplateId = null
        showDetail(PromptDetailState.Empty)
    }

    /** The only place this view leaves an invocation; it closes the shared session only when this view owns it. */
    private fun showDetail(detail: PromptDetailState) {
        if (showingInvocation) invocation.close()
        showingInvocation = false
        state.detail = detail
        view.renderDetail(detail)
        updateInteractionState()
    }

    override fun isDisposed(): Boolean = disposed || project.isDisposed

    override fun dispose() {
        disposed = true
        authorFlow.invalidateRequests()
        loadGenerations.invalidateDetailLoad()
    }
}
