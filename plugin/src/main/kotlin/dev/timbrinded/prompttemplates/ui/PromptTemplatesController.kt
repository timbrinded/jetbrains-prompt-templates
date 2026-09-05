package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
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
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.components.service
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
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
import dev.timbrinded.prompttemplates.core.TemplateDiagnostic
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSummary
import dev.timbrinded.prompttemplates.core.defaultVariableLabel
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import dev.timbrinded.prompttemplates.settings.PromptTemplatesWorkspaceState
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal interface PromptTemplatesView {
    val selectedDestinationFolder: Path

    fun renderLibrary(
        snapshot: LibrarySnapshot,
        bodyIndex: Map<Path, String>,
        selectedKey: LibrarySelectionKey?,
        expandedPaths: Collection<String>,
    )

    fun clearLibrarySelection()
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

internal class PromptTemplatesController(
    private val project: Project,
    private val view: PromptTemplatesView,
    private val settings: PromptTemplatesSettings,
    private val workspace: PromptTemplatesWorkspaceState,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val state = PromptToolWindowState(settings.libraryRoot)
    private var repository = FileSystemPromptTemplateRepository(settings.libraryRoot)
    private val projectService = project.service<PromptTemplatesProjectService>()
    private val invocation = projectService.invocation
    private var showingInvocation = invocation.state.value != null
    private val parser = LinearPlaceholderParser()
    private val loadGenerations = LoadGenerationTracker()
    private val authorRequests = AuthorAsyncRequestTracker()
    private var selectedKey: LibrarySelectionKey? =
        workspace.selectedTemplateId?.let(LibrarySelectionKey::Template)

    @Volatile
    private var disposed = false

    val authorOpen: Boolean
        get() = state.detail is PromptDetailState.Author

    fun start(parentDisposable: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            PromptTemplatesSettingsListener.TOPIC,
            PromptTemplatesSettingsListener(::onLibraryRootChanged),
        )
        coroutineScope.launch(Dispatchers.EDT) {
            projectService.libraryChanges.collect { onLibraryFilesChanged() }
        }
        coroutineScope.launch(Dispatchers.EDT) {
            invocation.state.collect { session ->
                if (!showingInvocation || session == null) return@collect
                val detail = PromptDetailState.Use(session)
                val previous = state.detail as? PromptDetailState.Use
                if (previous?.stored?.template?.id != detail.stored.template.id) {
                    selectedKey = LibrarySelectionKey.Template(detail.stored.template.id.value)
                    workspace.selectedTemplateId = detail.stored.template.id.value
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
        val saveInFlight = authorRequests.isSaveInProgress()
        authorRequests.invalidate()
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
            state.bodyIndex.clear()
            refreshTree()
        }
    }

    fun reloadLibrary(
        selection: LibrarySelectionKey? = selectedKey,
        reloadSelectedDetail: Boolean = false,
    ) {
        selectedKey = selection
        val generation = loadGenerations.beginLibraryLoad()
        val nextRepository = FileSystemPromptTemplateRepository(settings.libraryRoot)
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
                if (isDisposed() || !loadGenerations.isCurrentLibraryLoad(generation)) return@withContext
                if (hasLibraryRootChanged(state.librarySnapshot.root, scanned.root)) {
                    applyLibraryRootTransition(scanned.root, clearTree = false)
                }
                repository = nextRepository
                state.librarySnapshot = scanned
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

    private fun reconcileDetailAfterReload(
        selected: LibraryTreeSelection?,
        pendingDetail: TemplateDetailRequest?,
        templates: List<LibraryEntry.Template>,
        reloadSelectedDetail: Boolean,
    ) {
        if (authorOpen) return
        val active = state.detail as? PromptDetailState.Use
        if (active != null && pendingDetail == null && (
                reloadSelectedDetail ||
                    selected is LibraryTreeSelection.Template && selected.entry.summary.id == active.stored.template.id
                )) {
            invocation.checkTemplate()
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
                        TemplateDetailIntent.EDIT -> editStored(result.value)
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
        selectedKey = LibrarySelectionKey.Template(current.invocation.stored.template.id.value)
        workspace.selectedTemplateId = current.invocation.stored.template.id.value
        state.detail = PromptDetailState.Use(current)
        view.renderDetail(state.detail)
        refreshTree()
        updateInteractionState()
        return true
    }

    fun setInvocationValue(key: String, value: String) = invocation.setValue(key, value)

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
            UseViewAction.REFRESH_CONTEXT -> invocation.refreshContext()
            UseViewAction.RELOAD_TEMPLATE -> invocation.checkTemplate(reload = true)
            UseViewAction.SELECT_INSERTION_TARGET -> invocation.selectInsertionTarget()
            UseViewAction.RESET_VALUES -> {
                invocation.resetValues()
                invocation.state.value?.let { view.renderDetail(PromptDetailState.Use(it)) }
            }
        }
    }

    fun hasValidRenderedPrompt(): Boolean = invocation.renderedPayload() != null

    private fun deliver(copy: Boolean) {
        val destination = if (copy) invocation.copyRendered() else invocation.insertRendered()
        when (destination) {
            DestinationResult.Success -> PromptTemplatesNotifications.info(
                project,
                if (copy) "Prompt copied to the clipboard." else "Prompt inserted into the selected target.",
            )
            is DestinationResult.Failure -> {
                val error = invocation.state.value?.invocation?.render?.diagnostics
                    ?.firstOrNull { it.severity == DiagnosticSeverity.ERROR }
                if (error is TemplateDiagnostic.MissingRequiredValue) view.focusVariable(error.key)
                PromptTemplatesNotifications.error(project, destination.message)
            }
        }
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
        showAuthor(draftOf(use.stored), use.stored, use.stored.directory.parent)
    }

    private fun showAuthor(
        draft: PromptTemplateDraft,
        existing: StoredTemplate?,
        destination: Path,
    ) {
        authorRequests.invalidate()
        loadGenerations.invalidateDetailLoad()
        val author = TemplateAuthorState(
            draft = draft,
            existing = existing,
            destination = destination,
        )
        showDetail(PromptDetailState.Author(author))
    }

    fun cancelAuthor() {
        if (state.detail !is PromptDetailState.Author) return
        if (!view.confirmDiscardAuthor()) return
        authorRequests.invalidate()
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

    fun saveDraft(draft: PromptTemplateDraft) {
        val author = (state.detail as? PromptDetailState.Author)?.author ?: return
        val existing = author.existing
        val request = authorRequests.beginSave(author.destination) ?: return
        val repo = repository
        val libraryRootAtRequest = settings.libraryRoot
        coroutineScope.launch {
            // Every exit from this block, including early returns, exceptions and cancellation, must
            // release the save latch; otherwise later Save clicks are silently ignored.
            try {
                if (!authorRequests.isCurrent(request)) return@launch
                var result = withContext(Dispatchers.IO) {
                    if (existing == null) {
                        repo.create(draft, request.destination)
                    } else {
                        repo.update(existing.directory, draft, existing.revision)
                    }
                }
                if (result is RepositoryResult.Conflict && existing != null) {
                    val current = result.current
                    if (!confirmOverwrite(request, current, draft)) return@launch
                    if (!authorRequests.isCurrent(request)) return@launch
                    result = withContext(Dispatchers.IO) {
                        repo.update(existing.directory, draft, current.revision)
                    }
                }
                withContext(Dispatchers.EDT) {
                    if (isDisposed()) return@withContext
                    val rootChanged = hasLibraryRootChanged(libraryRootAtRequest, settings.libraryRoot)
                    if (rootChanged || !authorRequests.isCurrent(request)) {
                        // The files are on disk already; never drop that outcome silently.
                        if (rootChanged) authorRequests.invalidate()
                        reportSupersededSave(result, savedAuthor = author, rootChanged = rootChanged)
                        return@withContext
                    }
                    // Release on the EDT before showing the outcome so the next Save click is accepted at once;
                    // the finally below covers every other exit.
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
            } finally {
                authorRequests.finishSave(request)
            }
        }
    }

    private suspend fun confirmOverwrite(
        request: AuthorAsyncRequest,
        current: StoredTemplate,
        draft: PromptTemplateDraft,
    ): Boolean = withContext(Dispatchers.EDT) {
        !isDisposed() && authorRequests.isCurrent(request) && TemplateOverwriteDialog(project, current, draft).showAndGet()
    }

    fun importMarkdown(destination: Path = view.selectedDestinationFolder) {
        if (!canChangeLibrary()) return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("md")
            .withTitle("Import Prompt Template Markdown")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val request = authorRequests.begin(destination)
        coroutineScope.launch {
            val markdown = withContext(Dispatchers.IO) { readMarkdown(file) }
            withContext(Dispatchers.EDT) {
                if (isDisposed() || !authorRequests.isCurrent(request)) return@withContext
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
            LibraryTreeCommand.RENAME_FOLDER -> (target as? LibraryTreeSelection.Folder)?.let(::renameFolder)
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
            operation = { repo -> repo.createFolder(parent, name) },
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
            operation = { repo -> repo.renameFolder(target.directory, newName) },
            successMessage = "Folder renamed to '$newName'.",
            afterSuccess = { directory ->
                val newRelative = portableRelativePath(settings.libraryRoot, directory)
                workspace.replaceExpandedFolderPaths(remapExpandedPaths(
                    workspace.expandedFolderPaths,
                    oldRelative,
                    newRelative,
                ))
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
            operation = { repo -> repo.moveEntry(source.directory, destination, placement) },
            successMessage = "Library entry moved.",
            afterSuccess = { movedDirectory ->
                val newRelative = portableRelativePath(settings.libraryRoot, movedDirectory)
                if (source is LibraryTreeSelection.Folder) {
                    // Keep the moved folder open by also opening the destination chain it now sits under.
                    val remapped = remapExpandedPaths(workspace.expandedFolderPaths, oldRelative, newRelative)
                    workspace.replaceExpandedFolderPaths((remapped + ancestorPortablePaths(newRelative)).distinct())
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

    private fun editStored(stored: StoredTemplate) {
        showAuthor(draftOf(stored), stored, stored.directory.parent)
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
        if (settings.confirmDeletion) {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete '$name' and its source files?",
                "Delete Prompt Template",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        runRepositoryOperation(
            operation = { repo -> repo.deleteTemplate(directory) },
            successMessage = "Prompt template deleted.",
            afterSuccess = {
                clearSelectedTemplate()
                reloadLibrary(null)
            },
        )
    }

    private fun deleteFolder(target: LibraryTreeSelection.Folder) {
        if (!canChangeLibrary()) return
        val requestRepository = repository
        val requestRoot = requestRepository.root
        state.mutationInProgress = true
        updateInteractionState()
        coroutineScope.launch {
            val previewResult = withContext(Dispatchers.IO) {
                requestRepository.previewFolderDeletion(target.directory)
            }
            withContext(Dispatchers.EDT) {
                if (isDisposed()) return@withContext
                state.mutationInProgress = false
                updateInteractionState()
                if (hasLibraryRootChanged(requestRoot, settings.libraryRoot)) return@withContext
                when (previewResult) {
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, previewResult.message)
                    is RepositoryResult.Success -> confirmFolderDeletion(
                        target,
                        previewResult.value,
                        requestRepository,
                    )
                }
            }
        }
    }

    private fun confirmFolderDeletion(
        target: LibraryTreeSelection.Folder,
        preview: FolderDeletionPreview,
        requestRepository: FileSystemPromptTemplateRepository,
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
            requestRepository = requestRepository,
            operation = { repo -> repo.deleteFolder(preview) },
            successMessage = "Folder '$name' deleted.",
            afterSuccess = {
                val deletedPath = portableRelativePath(settings.libraryRoot, target.directory)
                workspace.replaceExpandedFolderPaths(workspace.expandedFolderPaths.filterNot { path ->
                    path == deletedPath || path.startsWith("$deletedPath/")
                })
                clearSelectedTemplate()
                reloadLibrary(null)
            },
        )
    }

    private fun exportTemplate() {
        val use = state.detail as? PromptDetailState.Use ?: return
        val destination = chooseDestination(slug(use.stored.template.metadata.name) + ".md") ?: return
        runRepositoryOperation(
            operation = { repo -> repo.exportTemplateMarkdown(use.stored.directory, destination) },
            successMessage = "Template Markdown exported to $destination.",
        )
    }

    private fun exportRendered() {
        val use = state.detail as? PromptDetailState.Use ?: return
        val usageRoot = settings.libraryRoot
        val payload = invocation.renderedPayload()
        if (payload == null) {
            PromptTemplatesNotifications.error(project, invocation.state.value?.deliveryProblem ?: "Choose a template first.")
            return
        }
        val destination = chooseDestination(slug(use.stored.template.metadata.name) + "-rendered.md") ?: return
        runRepositoryOperation(
            operation = { repo ->
                repo.exportRenderedMarkdown(payload, destination).also { result ->
                    if (result is RepositoryResult.Success) settings.recordUse(use.stored.template.id.value, usageRoot)
                }
            },
            successMessage = "Rendered Markdown exported to $destination.",
        )
    }

    private fun chooseDestination(suggestedName: String): Path? {
        val descriptor = FileSaverDescriptor("Export Markdown", "Choose where to export the Markdown file", "md")
        val baseDirectory: VirtualFile? = null
        return FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(baseDirectory, suggestedName)
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
        requestRepository: FileSystemPromptTemplateRepository = repository,
        operation: (FileSystemPromptTemplateRepository) -> RepositoryResult<T>,
        successMessage: String,
        afterSuccess: (T) -> Unit = {},
    ) {
        if (state.mutationInProgress) return
        val requestRoot = requestRepository.root
        state.mutationInProgress = true
        updateInteractionState()
        coroutineScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { runRepositoryOperationSafely { operation(requestRepository) } }
            } catch (cancelled: ProcessCanceledException) {
                resetMutationAfterCancellation()
                throw cancelled
            } catch (cancelled: CancellationException) {
                resetMutationAfterCancellation()
                throw cancelled
            }
            withContext(Dispatchers.EDT) {
                if (isDisposed()) return@withContext
                state.mutationInProgress = false
                updateInteractionState()
                val rootChanged = hasLibraryRootChanged(requestRoot, settings.libraryRoot)
                when (result) {
                    is RepositoryResult.Success -> {
                        if (rootChanged) {
                            PromptTemplatesNotifications.warning(
                                project,
                                "$successMessage The operation used the previous library at '$requestRoot'. " +
                                    "The current library view was not changed.",
                            )
                        } else {
                            PromptTemplatesNotifications.info(project, successMessage)
                            afterSuccess(result.value)
                        }
                        showWarnings(result.warnings)
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private suspend fun resetMutationAfterCancellation() {
        withContext(NonCancellable + Dispatchers.EDT) {
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

    /**
     * A save whose files were written before the library root changed or a newer author action superseded it.
     * [savedAuthor] identifies the author session that issued the save; only that session's draft is closed.
     */
    private fun reportSupersededSave(
        result: RepositoryResult<StoredTemplate>,
        savedAuthor: TemplateAuthorState,
        rootChanged: Boolean,
    ) {
        when (result) {
            is RepositoryResult.Success -> {
                val saved = result.value
                val openAuthor = (state.detail as? PromptDetailState.Author)?.author
                val closeDraft = rootChanged && openAuthor != null && openAuthor.draft == savedAuthor.draft
                PromptTemplatesNotifications.warning(
                    project,
                    "'${saved.template.metadata.name}' was saved to '${saved.directory}'." +
                        if (closeDraft) " The library location changed afterwards, so the draft was closed to avoid saving it twice." else "",
                )
                if (closeDraft) clearSelectedTemplate()
            }
            is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
        }
        // A root change already reloads the new library; otherwise show the files that were just written.
        if (!rootChanged) reloadLibrary(reloadSelectedDetail = true)
    }

    private fun clearSelectedTemplate() {
        loadGenerations.invalidateDetailLoad()
        selectedKey = null
        view.clearLibrarySelection()
        workspace.selectedTemplateId = null
        showDetail(PromptDetailState.Empty)
    }

    private fun showDetail(detail: PromptDetailState) {
        showingInvocation = false
        invocation.close()
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

private fun readMarkdown(file: VirtualFile): Result<String> = try {
    Result.success(Files.readString(file.toNioPath()))
} catch (exception: IOException) {
    Result.failure(exception)
} catch (exception: SecurityException) {
    Result.failure(exception)
} catch (exception: UnsupportedOperationException) {
    Result.failure(exception)
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
