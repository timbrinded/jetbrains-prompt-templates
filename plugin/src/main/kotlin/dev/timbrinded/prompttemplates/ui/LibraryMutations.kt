package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.FolderDeletionPreview
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesWorkspaceState
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What [LibraryMutations] needs from the tool window whose library it changes. */
internal interface LibraryMutationHost {
    val repository: FileSystemPromptTemplateRepository
    val librarySnapshot: LibrarySnapshot
    val selectedKey: LibrarySelectionKey?

    /** Whether the library may change now; tells the user why not. */
    fun canChangeLibrary(): Boolean
    fun isCurrentLibraryRoot(root: Path): Boolean
    fun mutationStateChanged()

    /**
     * Reloads after a finished mutation. Its [preferred] selection, or clearing the selection when it is null,
     * applies only if the selection still names the entry it named as [keyAtStart].
     */
    fun reloadAfterMutation(preferred: LibrarySelectionKey?, keyAtStart: LibrarySelectionKey?)
    fun isDisposed(): Boolean
}

/** The tool window's changes to library files: create, rename, move and delete entries, and exports. */
internal class LibraryMutations(
    private val project: Project,
    private val host: LibraryMutationHost,
    private val settings: PromptTemplatesSettings,
    private val workspace: PromptTemplatesWorkspaceState,
    private val projectService: PromptTemplatesProjectService,
    private val coroutineScope: CoroutineScope,
) {
    /** True while a change runs; other changes are refused meanwhile. */
    var inProgress = false
        private set

    fun createFolder(parent: Path) {
        if (!host.canChangeLibrary()) return
        val name = Messages.showInputDialog(
            project,
            "Folder name:",
            "New Prompt Template Folder",
            Messages.getQuestionIcon(),
        )?.trim()?.takeIf(String::isNotEmpty) ?: return
        val keyAtStart = host.selectedKey
        runRepositoryOperation(
            operation = { repo -> repo.createFolder(parent, name) },
            successMessage = "Folder '$name' created.",
            afterSuccess = { directory ->
                host.reloadAfterMutation(LibrarySelectionKey.Folder(portableRelativePath(settings.libraryRoot, directory)), keyAtStart)
            },
        )
    }

    fun renameFolder(target: LibraryTreeSelection.Folder) {
        if (!host.canChangeLibrary()) return
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
        val keyAtStart = host.selectedKey
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
                host.reloadAfterMutation(LibrarySelectionKey.Folder(newRelative), keyAtStart)
            },
        )
    }

    fun moveToFolder(source: LibraryTreeSelection) {
        if (!host.canChangeLibrary()) return
        val snapshot = host.librarySnapshot
        val folders = libraryFolders(snapshot).filterNot { candidate ->
            source is LibraryTreeSelection.Folder &&
                (candidate == source.directory || candidate.startsWith(source.directory))
        }
        if (folders.isEmpty()) return
        chooseLibraryFolder("Move Library Entry", snapshot.root, folders, initial = source.directory.parent) { destination ->
            if (source.directory.parent != destination) {
                moveEntry(source, destination, EntryPlacement.EndOfKind)
            }
        }
    }

    fun moveSibling(source: LibraryTreeSelection, direction: MoveDirection) {
        if (!host.canChangeLibrary()) return
        val move = siblingMove(host.librarySnapshot, source, direction) ?: return
        moveEntry(source, move.destination, move.placement)
    }

    fun moveEntry(source: LibraryTreeSelection, destination: Path, placement: EntryPlacement) {
        if (!host.canChangeLibrary()) return
        val root = host.librarySnapshot.root
        val keyBeforeMove = selectionKey(source, root)
        val oldRelative = portableRelativePath(root, source.directory)
        val keyAtStart = host.selectedKey
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
                host.reloadAfterMutation(preferred, keyAtStart)
            },
        )
    }

    fun deleteTemplate(name: String, directory: Path, expectedId: TemplateId?) {
        if (!host.canChangeLibrary()) return
        if (settings.confirmDeletion) {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete '$name' and its source files?",
                "Delete Prompt Template",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        val keyAtStart = host.selectedKey
        runRepositoryOperation(
            operation = { repo -> repo.deleteTemplate(directory, expectedId) },
            successMessage = "Prompt template deleted.",
            afterSuccess = { host.reloadAfterMutation(preferred = null, keyAtStart) },
        )
    }

    fun deleteFolder(target: LibraryTreeSelection.Folder) {
        if (!host.canChangeLibrary()) return
        val requestRepository = host.repository
        val requestRoot = requestRepository.root
        // The selection can change while the preview is read, so the deletion compares against this one.
        val keyAtStart = host.selectedKey
        setInProgress(true)
        coroutineScope.launch {
            val previewResult = withContext(Dispatchers.IO) {
                requestRepository.previewFolderDeletion(target.directory)
            }
            withContext(Dispatchers.EDT) {
                if (host.isDisposed()) return@withContext
                setInProgress(false)
                if (!host.isCurrentLibraryRoot(requestRoot)) return@withContext
                when (previewResult) {
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, previewResult.message)
                    is RepositoryResult.Success -> confirmFolderDeletion(
                        target,
                        previewResult.value,
                        requestRepository,
                        keyAtStart,
                    )
                }
            }
        }
    }

    private fun confirmFolderDeletion(
        target: LibraryTreeSelection.Folder,
        preview: FolderDeletionPreview,
        requestRepository: FileSystemPromptTemplateRepository,
        keyAtStart: LibrarySelectionKey?,
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
                host.reloadAfterMutation(preferred = null, keyAtStart)
            },
        )
    }

    fun exportTemplate(stored: StoredTemplate) {
        val destination = chooseDestination(slug(stored.template.metadata.name) + ".md") ?: return
        runRepositoryOperation(
            operation = { repo -> repo.exportTemplateMarkdown(stored.directory, destination) },
            successMessage = "Template Markdown exported to $destination.",
            changesLibrary = false,
        )
    }

    /** Exports [payload], the inspected rendered prompt of [stored]. */
    fun exportRendered(stored: StoredTemplate, payload: String) {
        val usageRoot = settings.libraryRoot
        val destination = chooseDestination(slug(stored.template.metadata.name) + "-rendered.md") ?: return
        runRepositoryOperation(
            operation = { repo ->
                repo.exportRenderedMarkdown(payload, destination).also { result ->
                    if (result is RepositoryResult.Success) settings.recordUse(stored.template.id.value, usageRoot)
                }
            },
            successMessage = "Rendered Markdown exported to $destination.",
            changesLibrary = false,
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

    private fun <T> runRepositoryOperation(
        requestRepository: FileSystemPromptTemplateRepository = host.repository,
        operation: (FileSystemPromptTemplateRepository) -> RepositoryResult<T>,
        successMessage: String,
        changesLibrary: Boolean = true,
        afterSuccess: (T) -> Unit = {},
    ) {
        if (inProgress) return
        val requestRoot = requestRepository.root
        setInProgress(true)
        coroutineScope.launch {
            val result = try {
                val run = { runRepositoryOperationSafely { operation(requestRepository) } }
                if (changesLibrary) projectService.writeLibrary(requestRoot, run) else withContext(Dispatchers.IO) { run() }
            } catch (cancelled: ProcessCanceledException) {
                resetAfterCancellation()
                throw cancelled
            } catch (cancelled: CancellationException) {
                resetAfterCancellation()
                throw cancelled
            }
            withContext(Dispatchers.EDT) {
                if (host.isDisposed()) return@withContext
                setInProgress(false)
                val rootChanged = !host.isCurrentLibraryRoot(requestRoot)
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
                        showRepositoryWarnings(project, result.warnings)
                    }
                    is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
                }
            }
        }
    }

    private suspend fun resetAfterCancellation() {
        withContext(NonCancellable + Dispatchers.EDT) {
            if (!host.isDisposed()) setInProgress(false)
        }
    }

    private fun setInProgress(value: Boolean) {
        inProgress = value
        host.mutationStateChanged()
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "prompt" }
}

/** The library root and every organiser folder, in tree order. */
internal fun libraryFolders(snapshot: LibrarySnapshot): List<Path> =
    listOf(snapshot.root) + flattenFolders(snapshot.children).map(LibraryEntry.Folder::directory)

/** Lets the user pick one of [folders] under [root], with [initial] preselected when it is one of them. */
internal fun chooseLibraryFolder(
    title: String,
    root: Path,
    folders: List<Path>,
    initial: Path,
    onChosen: (Path) -> Unit,
) {
    val options = folders.map { directory -> if (directory == root) "/ (Library root)" else portableRelativePath(root, directory) }
    val initialIndex = folders.indexOf(initial).takeIf { it >= 0 } ?: 0
    JBPopupFactory.getInstance()
        .createPopupChooserBuilder(options)
        .setTitle(title)
        .setSelectedValue(options[initialIndex], true)
        .setItemChosenCallback { choice -> onChosen(folders[options.indexOf(choice)]) }
        .createPopup()
        .showInFocusCenter()
}

internal fun showRepositoryWarnings(project: Project, warnings: List<String>) {
    warnings.forEach { PromptTemplatesNotifications.warning(project, it) }
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
