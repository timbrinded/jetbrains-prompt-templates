package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import java.nio.file.Path

internal fun isPromptLibraryChange(root: Path, eventPath: String): Boolean {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val changedPath = runCatching { Path.of(eventPath).toAbsolutePath().normalize() }.getOrNull() ?: return false
    if (!changedPath.startsWith(normalizedRoot)) return false

    val relativePath = normalizedRoot.relativize(changedPath)
    if (relativePath.nameCount <= 1) return true
    return changedPath.fileName.toString() in CANONICAL_TEMPLATE_FILES
}

internal class LibraryFileWatcher(
    project: Project,
    root: Path,
    parentDisposable: Disposable,
    private val onChanged: () -> Unit,
) : Disposable {
    private val normalizedRoot = root.toAbsolutePath().normalize()
    private val localFileSystem = LocalFileSystem.getInstance()
    private val watchRequest = localFileSystem.addRootToWatch(normalizedRoot.toString(), true)

    init {
        Disposer.register(parentDisposable, this)
        project.messageBus.connect(parentDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { event -> isPromptLibraryChange(normalizedRoot, event.path) }) onChanged()
                }
            },
        )
    }

    override fun dispose() {
        watchRequest?.let(localFileSystem::removeWatchedRoot)
    }
}

private val CANONICAL_TEMPLATE_FILES = setOf(
    FileSystemPromptTemplateRepository.MARKDOWN_FILE,
    FileSystemPromptTemplateRepository.METADATA_FILE,
)
