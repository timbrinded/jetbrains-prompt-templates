package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun isPromptLibraryChange(root: Path, eventPath: String): Boolean =
    libraryRootsOrNull(root)?.let { roots -> isPromptLibraryChange(roots, eventPath) } ?: false

internal fun isPromptLibraryChange(roots: List<Path>, eventPath: String): Boolean {
    val changedPath = eventPathOrNull(eventPath) ?: return false
    if (!isManagedLibraryPath(roots, changedPath)) return false

    return changedPath.name in LIBRARY_CONTROL_FILES
}

internal fun isPromptLibraryChange(root: Path, event: VFileEvent): Boolean =
    libraryRootsOrNull(root)?.let { roots -> isPromptLibraryChange(roots, event) } ?: false

/** Resolve the roots once per event batch with [libraryRootsOrNull]; resolving per event costs a realpath lookup each. */
internal fun isPromptLibraryChange(roots: List<Path>, event: VFileEvent): Boolean {
    val paths = buildList {
        add(event.path)
        if (event is VFileMoveEvent) {
            add(event.oldParent.toNioPath().resolve(event.file.name).toString())
            add(event.newParent.toNioPath().resolve(event.file.name).toString())
        }
        if (event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME) {
            event.file.parent?.toNioPath()?.resolve(event.oldValue.toString())?.let { add(it.toString()) }
        }
    }
    val directoryEvent = event.file?.isDirectory == true || event is VFileCreateEvent && event.isDirectory
    return isPromptLibraryChange(roots, paths, directoryEvent)
}

internal fun isPromptLibraryChange(
    root: Path,
    eventPaths: Collection<String>,
    directoryEvent: Boolean,
): Boolean = libraryRootsOrNull(root)?.let { roots -> isPromptLibraryChange(roots, eventPaths, directoryEvent) } ?: false

internal fun isPromptLibraryChange(
    roots: List<Path>,
    eventPaths: Collection<String>,
    directoryEvent: Boolean,
): Boolean {
    if (eventPaths.any { isPromptLibraryChange(roots, it) }) return true
    if (!directoryEvent) return false
    return eventPaths.any { eventPath ->
        val changedPath = eventPathOrNull(eventPath) ?: return@any false
        isManagedLibraryPath(roots, changedPath)
    }
}

/**
 * The configured root and, when the root is a symbolic link, its real path. VFS and native watcher
 * events may report either form, and README documents that the configured root may be a link.
 */
internal fun libraryRootsOrNull(root: Path): List<Path>? {
    val normalizedRoot = try {
        root.toAbsolutePath().normalize()
    } catch (_: SecurityException) {
        return null
    }
    val realRoot = try {
        normalizedRoot.toRealPath()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
    return if (realRoot == null || realRoot == normalizedRoot) listOf(normalizedRoot) else listOf(normalizedRoot, realRoot)
}

private fun eventPathOrNull(eventPath: String): Path? = try {
    Path.of(eventPath).toAbsolutePath().normalize()
} catch (_: InvalidPathException) {
    null
} catch (_: SecurityException) {
    null
}

private fun isManagedLibraryPath(roots: List<Path>, candidate: Path): Boolean {
    val root = roots.firstOrNull(candidate::startsWith) ?: return false
    return root.relativize(candidate).none { segment ->
        FileSystemPromptTemplateRepository.isInternalLibraryEntryName(segment.toString())
    }
}

internal data class LibraryWatchRegistration<MaterializedRoot, WatchRequest>(
    val materializedPath: Path?,
    val materializedRoot: MaterializedRoot?,
    val watchRequest: WatchRequest,
)

internal fun <MaterializedRoot, WatchRequest> registerLibraryWatch(
    root: Path,
    pathExists: (Path) -> Boolean = { path -> Files.exists(path) },
    materializeRoot: (Path) -> MaterializedRoot,
    addRecursiveWatch: (String, Boolean) -> WatchRequest,
): LibraryWatchRegistration<MaterializedRoot, WatchRequest> {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val materializedPath = nearestExistingAncestor(normalizedRoot, pathExists)
    val materializedRoot = materializedPath?.let(materializeRoot)
    val watchRequest = addRecursiveWatch(normalizedRoot.toString(), true)
    return LibraryWatchRegistration(materializedPath, materializedRoot, watchRequest)
}

internal fun nearestExistingAncestor(
    root: Path,
    pathExists: (Path) -> Boolean = { path -> Files.exists(path) },
): Path? =
    generateSequence(root.toAbsolutePath().normalize()) { candidate -> candidate.parent }
        .firstOrNull(pathExists)

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

    fun record(snapshot: LibraryPollSnapshot): Boolean {
        val priorSnapshot = previous
        previous = snapshot
        return priorSnapshot != null && priorSnapshot != snapshot
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
        if (!directoryAttributes.isDirectory || (!isRoot && directoryAttributes.isSymbolicLink)) continue
        entries += LibraryPollEntry(
            relativePath = relativePollPath(normalizedRoot, directory),
            directory = true,
        )

        val children = listDirectoryNoFollow(directory)
        val controlFiles = children.mapNotNull { child ->
            val attributes = readAttributesNoFollow(child) ?: return@mapNotNull null
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
        entries += controlFiles

        // A canonical template file makes this directory one template package. Its nested files and
        // directories are support data, not organiser nodes, so stop at the package boundary.
        if (controlFiles.any { entry -> entry.relativePath.substringAfterLast('/') in TEMPLATE_PACKAGE_FILES }) {
            continue
        }
        children.forEach { child ->
            if (FileSystemPromptTemplateRepository.isInternalLibraryEntryName(child.name)) return@forEach
            val attributes = readAttributesNoFollow(child) ?: return@forEach
            if (attributes.isDirectory && !attributes.isSymbolicLink) pendingDirectories.add(child)
        }
    }

    return LibraryPollSnapshot(
        entries.sortedWith(
            compareBy<LibraryPollEntry>(
                { entry -> entry.relativePath },
                { entry -> if (entry.directory) 0 else 1 },
            ),
        ),
    )
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

internal class LibraryFileWatcher(
    project: Project,
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
    private val normalizedRoot = root.toAbsolutePath().normalize()
    private val localFileSystem = LocalFileSystem.getInstance()
    private val watchRegistration = registerLibraryWatch(
        root = normalizedRoot,
        materializeRoot = localFileSystem::refreshAndFindFileByNioFile,
        addRecursiveWatch = localFileSystem::addRootToWatch,
    )
    private val pollChangeTracker = LibraryPollChangeTracker()
    private var reloadJob: Job? = null
    @Volatile
    private var watcherDisposed = false

    init {
        Disposer.register(parentDisposable, this)
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    val roots = libraryRootsOrNull(normalizedRoot) ?: return
                    if (events.any { event -> isPromptLibraryChange(roots, event) }) {
                        queueReload()
                    }
                }
            },
        )
        coroutineScope.launch(Dispatchers.IO) { pollLibrary() }
    }

    override fun dispose() {
        watcherDisposed = true
        coroutineScope.cancel()
        watchRegistration.watchRequest?.let(localFileSystem::removeWatchedRoot)
    }

    private suspend fun pollLibrary() {
        while (currentCoroutineContext().isActive) {
            try {
                val currentSnapshot = snapshotPromptLibrary(normalizedRoot)
                if (pollChangeTracker.record(currentSnapshot)) queueReload()
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
    FileSystemPromptTemplateRepository.ORDER_FILE,
)

private val TEMPLATE_PACKAGE_FILES = setOf(
    FileSystemPromptTemplateRepository.MARKDOWN_FILE,
    FileSystemPromptTemplateRepository.METADATA_FILE,
)
