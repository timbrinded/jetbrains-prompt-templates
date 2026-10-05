package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.RepositoryResult
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class LibraryPollEntry(
    val relativePath: String,
    val directory: Boolean,
    val size: Long? = null,
    val modifiedAt: FileTime? = null,
    val fileKey: String? = null,
)

internal data class LibraryPollSnapshot(val entries: List<LibraryPollEntry>)

internal class LibraryPollChangeTracker {
    private var previous: LibraryPollSnapshot? = null

    /** Whether [snapshot] differs from the last snapshot recorded or accepted. */
    fun record(snapshot: LibraryPollSnapshot): Boolean {
        val priorSnapshot = previous
        previous = snapshot
        return priorSnapshot != null && priorSnapshot != snapshot
    }

    /** Takes [snapshot] as the known state without reporting it, after a change the plugin made itself. */
    fun accept(snapshot: LibraryPollSnapshot) {
        previous = snapshot
    }
}

private fun readRootAttributes(root: Path): BasicFileAttributes? = try {
    Files.readAttributes(root, BasicFileAttributes::class.java)
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

internal fun snapshotPromptLibrary(root: Path): LibraryPollSnapshot {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val pendingDirectories = ArrayDeque<Path>()
    val entries = mutableListOf<LibraryPollEntry>()
    pendingDirectories.add(normalizedRoot)

    while (pendingDirectories.isNotEmpty()) {
        val directory = pendingDirectories.removeFirst()
        // The configured root may itself be a symbolic link (see README); entries below it may not.
        val isRoot = directory == normalizedRoot
        val directoryAttributes = (if (isRoot) readRootAttributes(directory) else readAttributesNoFollow(directory))
            ?: continue
        if (!directoryAttributes.isDirectory || (!isRoot && FileSystemPromptTemplateRepository.isLinkEntry(directory, directoryAttributes))) {
            continue
        }

        // One attribute read per child serves both the control-file records and the descent below.
        val children = listDirectoryNoFollow(directory).mapNotNull { child ->
            readAttributesNoFollow(child)?.let { attributes -> child to attributes }
        }
        val controlFiles = children.mapNotNull { (child, attributes) ->
            if (
                attributes.isSymbolicLink ||
                !attributes.isRegularFile ||
                child.name !in LIBRARY_CONTROL_FILES
            ) {
                return@mapNotNull null
            }
            LibraryPollEntry(
                relativePath = relativePollPath(normalizedRoot, child),
                directory = false,
                size = attributes.size(),
                modifiedAt = attributes.lastModifiedTime(),
                fileKey = attributes.fileKey()?.toString(),
            )
        }
        val isTemplatePackage = controlFiles.any { entry -> entry.relativePath.substringAfterLast('/') in TEMPLATE_PACKAGE_FILES }
        entries += LibraryPollEntry(
            relativePath = relativePollPath(normalizedRoot, directory),
            directory = true,
            // A UI scan can see a transient journal that appears and disappears between two polls.
            // Its parent mtime still changes, so the next poll clears that intermediate recovery state.
            modifiedAt = directoryAttributes.lastModifiedTime().takeIf { isTemplatePackage },
        )
        entries += controlFiles

        // A canonical template file makes this directory one template package. Its nested files and
        // directories are support data, not organiser nodes, so stop at the package boundary.
        if (isTemplatePackage) {
            continue
        }
        children.forEach { (child, attributes) ->
            if (FileSystemPromptTemplateRepository.isInternalLibraryEntryName(child.name)) return@forEach
            // Directory junctions report as directories; never descend into a link out of (or back into) the library.
            if (attributes.isDirectory && !FileSystemPromptTemplateRepository.isLinkEntry(child, attributes)) {
                pendingDirectories.add(child)
            }
        }
    }

    return LibraryPollSnapshot(entries.sortedBy(LibraryPollEntry::relativePath))
}

private fun relativePollPath(root: Path, path: Path): String =
    root.relativize(path.toAbsolutePath().normalize()).joinToString("/") { segment -> segment.toString() }

private fun readAttributesNoFollow(path: Path): BasicFileAttributes? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun listDirectoryNoFollow(directory: Path): List<Path> = try {
    Files.newDirectoryStream(directory).use { stream -> stream.toList() }
} catch (_: IOException) {
    emptyList()
} catch (_: DirectoryIteratorException) {
    emptyList()
} catch (_: SecurityException) {
    emptyList()
}

/**
 * Detects library changes made outside this project window by polling [snapshotPromptLibrary]. The poll is the
 * only detection path: it also covers symbolic-link roots and file systems without native watching, and the
 * plugin's own writes run through [ownWrite] so they are never reported back as external changes.
 */
internal class LibraryFileWatcher(
    root: Path,
    parentDisposable: Disposable,
    parentScope: CoroutineScope,
    private val onChanged: () -> Unit,
) : Disposable {
    private val coroutineScope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            CoroutineName("LibraryFileWatcher"),
    )
    val root: Path = root.toAbsolutePath().normalize()
    private val pollChangeTracker = LibraryPollChangeTracker()
    private val pollLock = Mutex()
    private var watchRequest: LocalFileSystem.WatchRequest? = null
    private var reloadJob: Job? = null
    @Volatile
    private var watcherDisposed = false

    init {
        Disposer.register(parentDisposable, this)
        coroutineScope.launch(Dispatchers.IO) {
            // Native watching keeps editors that show library files current; registering it may touch the disk.
            watchRoot()
            pollLibrary()
        }
    }

    @Synchronized
    override fun dispose() {
        watcherDisposed = true
        coroutineScope.cancel()
        watchRequest?.let(LocalFileSystem.getInstance()::removeWatchedRoot)
        watchRequest = null
    }

    @Synchronized
    private fun watchRoot() {
        if (!watcherDisposed) watchRequest = LocalFileSystem.getInstance().addRootToWatch(root.toString(), true)
    }

    /**
     * Runs one of the plugin's own writes to this library. Polls wait for it, and a successful write becomes the
     * known state, so the next poll reports only changes made elsewhere. Changes made elsewhere since the last
     * poll are reported first, so the new baseline does not absorb them.
     */
    suspend fun <T> ownWrite(write: () -> RepositoryResult<T>): RepositoryResult<T> = withContext(Dispatchers.IO) {
        pollLock.withLock {
            if (pollChangeTracker.record(snapshotPromptLibrary(root))) queueReload()
            write().also { result ->
                if (result is RepositoryResult.Success) pollChangeTracker.accept(snapshotPromptLibrary(root))
            }
        }
    }

    private suspend fun pollLibrary() {
        while (currentCoroutineContext().isActive) {
            try {
                val changed = pollLock.withLock { pollChangeTracker.record(snapshotPromptLibrary(root)) }
                if (changed) queueReload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: RuntimeException) {
                LOG.warn("Unable to poll the prompt template library", exception)
            }
            delay(LIBRARY_POLL_INTERVAL)
        }
    }

    @Synchronized
    private fun queueReload() {
        if (watcherDisposed) return
        reloadJob?.cancel()
        reloadJob = coroutineScope.launch(Dispatchers.EDT) {
            delay(RELOAD_DEBOUNCE)
            if (!watcherDisposed) onChanged()
        }
    }
}

private val RELOAD_DEBOUNCE = 150.milliseconds
private val LIBRARY_POLL_INTERVAL = 2.seconds

private val LOG = logger<LibraryFileWatcher>()

private val LIBRARY_CONTROL_FILES = setOf(
    FileSystemPromptTemplateRepository.MARKDOWN_FILE,
    FileSystemPromptTemplateRepository.METADATA_FILE,
    FileSystemPromptTemplateRepository.SAVE_JOURNAL_FILE,
    FileSystemPromptTemplateRepository.ORDER_FILE,
)

private val TEMPLATE_PACKAGE_FILES = setOf(
    FileSystemPromptTemplateRepository.MARKDOWN_FILE,
    FileSystemPromptTemplateRepository.METADATA_FILE,
    FileSystemPromptTemplateRepository.SAVE_JOURNAL_FILE,
)
