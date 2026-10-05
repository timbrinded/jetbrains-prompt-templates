package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.core.TemplateReconciler
import dev.timbrinded.prompttemplates.core.WorkedExamples
import dev.timbrinded.prompttemplates.core.escapePlaceholderOpenings
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [AuthorFlow] needs from the tool window that shows its drafts. */
internal interface AuthorFlowHost {
    val repository: FileSystemPromptTemplateRepository
    val librarySnapshot: LibrarySnapshot

    /** The draft the tool window shows, if it shows one. */
    val openAuthor: TemplateAuthorState?

    /** Whether the library may change now; tells the user why not. */
    fun canChangeLibrary(): Boolean
    fun isCurrentLibraryRoot(root: Path): Boolean
    fun showAuthor(author: TemplateAuthorState)

    /** Shows [saved], the template a save just wrote, and selects it as [selection] in the reloaded library. */
    fun showSavedTemplate(saved: StoredTemplate, selection: LibrarySelectionKey)

    /** Closes the open draft and clears the selection. */
    fun closeAuthor()
    fun reloadLibraryAndDetail()
    fun isDisposed(): Boolean
}

/** Starts template drafts (new, imported, from a selection, from an example, duplicated or edited) and saves them. */
internal class AuthorFlow(
    private val project: Project,
    private val host: AuthorFlowHost,
    private val view: PromptTemplatesView,
    private val settings: PromptTemplatesSettings,
    private val projectService: PromptTemplatesProjectService,
    private val coroutineScope: CoroutineScope,
) {
    private val requests = AuthorAsyncRequestTracker()

    val saveInProgress: Boolean get() = requests.isSaveInProgress()

    /** Makes every pending author callback stale, such as a save that has not landed or an open chooser. */
    fun invalidateRequests() = requests.invalidate()

    fun startNewTemplate() = startNewTemplateAt(view.selectedDestinationFolder)

    fun startNewTemplateAt(destination: Path) {
        if (!host.canChangeLibrary()) return
        showAuthor(
            PromptTemplateDraft(
                name = "New prompt",
                markdown = "# New prompt\n\n{{objective}}\n",
            ),
            existing = null,
            destination = destination,
        )
    }

    fun browseExamples() {
        if (!host.canChangeLibrary()) return
        val destination = view.selectedDestinationFolder
        val request = requests.begin(destination)
        val dialog = WorkedExamplesDialog(project, destination, WorkedExamples.all)
        if (!dialog.showAndGet() || !requests.isCurrent(request) || !host.canChangeLibrary()) return
        val example = dialog.selectedExample
        val draft = example.newDraft(availableTemplateName(example.template.metadata.name, siblingNames(destination)))
        showAuthor(draft, existing = null, destination = destination)
        saveDraft(draft)
    }

    fun startTemplateFromSelection(text: String, sourceName: String) {
        if (!host.canChangeLibrary()) return
        val destination = view.selectedDestinationFolder
        val request = requests.begin(destination)
        val choice = if (text.contains("{{")) Messages.showDialog(
            project,
            "The selection contains {{...}}.\nPreserve the text literally, or interpret placeholders as input and IDE context variables.",
            "Create Template from Selection",
            arrayOf("Preserve Literally", "Interpret Placeholders", "Cancel"),
            0,
            Messages.getQuestionIcon(),
        ) else 0
        if (!requests.isCurrent(request) || !host.canChangeLibrary()) return
        val markdown = when (choice) {
            0 -> escapePlaceholderOpenings(text)
            1 -> text
            else -> return
        }
        showAuthor(
            PromptTemplateDraft(name = availableTemplateName("Selection from $sourceName", siblingNames(destination)), markdown = markdown),
            existing = null,
            destination = destination,
        )
    }

    fun importMarkdown(destination: Path = view.selectedDestinationFolder) {
        if (!host.canChangeLibrary()) return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("md")
            .withTitle("Import Prompt Template Markdown")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val request = requests.begin(destination)
        coroutineScope.launch {
            val markdown = withContext(Dispatchers.IO) { readMarkdown(file) }
            withContext(Dispatchers.EDT) {
                if (host.isDisposed() || !requests.isCurrent(request)) return@withContext
                markdown.onSuccess { body ->
                    val name = body.lineSequence().map(String::trim)
                        .firstOrNull { it.startsWith("# ") }
                        ?.removePrefix("# ")
                        ?.trim()
                        ?.ifBlank { null }
                        ?: file.nameWithoutExtension
                    val variables = TemplateReconciler().reconcile(body, emptyList()).variables
                    showAuthor(
                        PromptTemplateDraft(name = name, variables = variables, markdown = body),
                        existing = null,
                        destination = request.destination,
                    )
                }.onFailure { PromptTemplatesNotifications.error(project, "Unable to read Markdown: ${it.message}") }
            }
        }
    }

    fun duplicate(stored: StoredTemplate) {
        if (!host.canChangeLibrary()) return
        val snapshot = host.librarySnapshot
        val request = requests.begin(stored.directory.parent)
        chooseLibraryFolder("Duplicate Template in Folder", snapshot.root, libraryFolders(snapshot), initial = stored.directory.parent) { destination ->
            if (host.isDisposed() || !requests.isCurrent(request) || !host.canChangeLibrary()) return@chooseLibraryFolder
            val draft = draftOf(stored).copy(
                id = TemplateId.random(),
                name = availableTemplateName("${stored.template.metadata.name} copy", siblingNames(destination)),
            )
            showAuthor(draft, existing = null, destination = destination)
        }
    }

    fun edit(stored: StoredTemplate) {
        showAuthor(draftOf(stored), stored, stored.directory.parent)
    }

    fun saveDraft(draft: PromptTemplateDraft) {
        val author = host.openAuthor ?: return
        val existing = author.existing
        val request = requests.beginSave(author.destination) ?: return
        val repo = host.repository
        val libraryRootAtRequest = settings.libraryRoot
        coroutineScope.launch {
            // Every exit from this block, including early returns, exceptions and cancellation, must
            // release the save latch; otherwise later Save clicks are silently ignored.
            try {
                if (!requests.isCurrent(request)) return@launch
                var result = projectService.writeLibrary(repo.root) {
                    if (existing == null) {
                        repo.create(draft, request.destination)
                    } else {
                        repo.update(existing.directory, draft, existing.revision)
                    }
                }
                if (result is RepositoryResult.Conflict && existing != null) {
                    val current = result.current
                    if (!confirmOverwrite(request, current, draft)) return@launch
                    if (!requests.isCurrent(request)) return@launch
                    result = projectService.writeLibrary(repo.root) {
                        repo.update(existing.directory, draft, current.revision)
                    }
                }
                withContext(Dispatchers.EDT) {
                    if (host.isDisposed()) return@withContext
                    val rootChanged = !host.isCurrentLibraryRoot(libraryRootAtRequest)
                    if (rootChanged || !requests.isCurrent(request)) {
                        // The files are on disk already; never drop that outcome silently.
                        if (rootChanged) requests.invalidate()
                        reportSupersededSave(result, savedAuthor = author, rootChanged = rootChanged)
                        return@withContext
                    }
                    // Release on the EDT before showing the outcome so the next Save click is accepted at once;
                    // the finally below covers every other exit.
                    requests.finishSave(request)
                    when (result) {
                        is RepositoryResult.Success -> {
                            showRepositoryWarnings(project, result.warnings)
                            host.showSavedTemplate(
                                result.value,
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
                requests.finishSave(request)
            }
        }
    }

    private suspend fun confirmOverwrite(
        request: AuthorAsyncRequest,
        current: StoredTemplate,
        draft: PromptTemplateDraft,
    ): Boolean = withContext(Dispatchers.EDT) {
        !host.isDisposed() && requests.isCurrent(request) && TemplateOverwriteDialog(project, current, draft).showAndGet()
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
                val openAuthor = host.openAuthor
                val closeDraft = rootChanged && openAuthor != null && openAuthor.draft == savedAuthor.draft
                PromptTemplatesNotifications.warning(
                    project,
                    "'${saved.template.metadata.name}' was saved to '${saved.directory}'." +
                        if (closeDraft) " The library location changed afterwards, so the draft was closed to avoid saving it twice." else "",
                )
                if (closeDraft) host.closeAuthor()
            }
            is RepositoryResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
        }
        // A root change already reloads the new library; otherwise show the files that were just written.
        if (!rootChanged) host.reloadLibraryAndDetail()
    }

    private fun showAuthor(
        draft: PromptTemplateDraft,
        existing: StoredTemplate?,
        destination: Path,
    ) {
        requests.invalidate()
        host.showAuthor(
            TemplateAuthorState(
                draft = draft,
                existing = existing,
                destination = destination,
            ),
        )
    }

    private fun siblingNames(destination: Path): List<String> {
        val snapshot = host.librarySnapshot
        val children = if (destination == snapshot.root) snapshot.children
        else flattenFolders(snapshot.children).firstOrNull { it.directory == destination }?.children.orEmpty()
        return children.map(LibraryEntry::displayName)
    }

    private fun draftOf(stored: StoredTemplate) = PromptTemplateDraft(
        id = stored.template.id,
        name = stored.template.metadata.name,
        description = stored.template.metadata.description,
        tags = stored.template.metadata.tags,
        variables = stored.template.metadata.variables,
        markdown = stored.template.markdown,
    )
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
