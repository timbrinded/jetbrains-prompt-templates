package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.ArrayDeque

internal fun isPromptLibraryChange(root: Path, eventPath: String): Boolean {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val changedPath = runCatching { Path.of(eventPath).toAbsolutePath().normalize() }.getOrNull() ?: return false
    if (!isManagedLibraryPath(normalizedRoot, changedPath)) return false

    return changedPath.fileName.toString() in LIBRARY_CONTROL_FILES
}

internal fun isPromptLibraryChange(root: Path, event: VFileEvent): Boolean {
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
    return isPromptLibraryChange(root, paths, directoryEvent)
}

internal fun isPromptLibraryChange(
    root: Path,
    eventPaths: Collection<String>,
    directoryEvent: Boolean,
): Boolean {
    if (eventPaths.any { isPromptLibraryChange(root, it) }) return true
    if (!directoryEvent) return false
    return eventPaths.any { eventPath ->
        runCatching {
            isManagedLibraryPath(
                root.toAbsolutePath().normalize(),
                Path.of(eventPath).toAbsolutePath().normalize(),
            )
        }.getOrDefault(false)
    }
}

private fun isManagedLibraryPath(root: Path, candidate: Path): Boolean {
    if (!candidate.startsWith(root)) return false
    return root.relativize(candidate).none { segment ->
        FileSystemPromptTemplateRepository.isLibraryManagementDirectoryName(segment.toString())
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

internal fun snapshotPromptLibrary(root: Path): LibraryPollSnapshot {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val pendingDirectories = ArrayDeque<Path>()
    val entries = mutableListOf<LibraryPollEntry>()
    pendingDirectories.add(normalizedRoot)

    while (pendingDirectories.isNotEmpty()) {
        val directory = pendingDirectories.removeFirst()
        val directoryAttributes = readAttributesNoFollow(directory) ?: continue
        if (!directoryAttributes.isDirectory || directoryAttributes.isSymbolicLink) continue
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
                child.fileName.toString() !in LIBRARY_CONTROL_FILES
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
            if (FileSystemPromptTemplateRepository.isLibraryManagementDirectoryName(child.fileName.toString())) return@forEach
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
    private val onChanged: () -> Unit,
) : Disposable {
    private val normalizedRoot = root.toAbsolutePath().normalize()
    private val localFileSystem = LocalFileSystem.getInstance()
    private val watchRegistration = registerLibraryWatch(
        root = normalizedRoot,
        materializeRoot = localFileSystem::refreshAndFindFileByNioFile,
        addRecursiveWatch = localFileSystem::addRootToWatch,
    )
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val pollChangeTracker = LibraryPollChangeTracker()
    @Volatile
    private var watcherDisposed = false

    init {
        Disposer.register(parentDisposable, this)
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { event -> isPromptLibraryChange(normalizedRoot, event) }) {
                        queueReload()
                    }
                }
            },
        )
        scheduleNextPoll(0)
    }

    override fun dispose() {
        watcherDisposed = true
        pollAlarm.cancelAllRequests()
        reloadAlarm.cancelAllRequests()
        watchRegistration.watchRequest?.let(localFileSystem::removeWatchedRoot)
    }

    private fun scheduleNextPoll(delayMillis: Int) {
        if (watcherDisposed) return
        pollAlarm.addRequest(
            {
                if (watcherDisposed) return@addRequest
                try {
                    val currentSnapshot = snapshotPromptLibrary(normalizedRoot)
                    if (pollChangeTracker.record(currentSnapshot)) {
                        queueReload()
                    }
                } finally {
                    scheduleNextPoll(LIBRARY_POLL_INTERVAL_MS)
                }
            },
            delayMillis,
        )
    }

    @Synchronized
    private fun queueReload() {
        if (watcherDisposed) return
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest(onChanged, RELOAD_DEBOUNCE_MS)
    }
}

private const val RELOAD_DEBOUNCE_MS = 150
private const val LIBRARY_POLL_INTERVAL_MS = 2_000

private val LIBRARY_CONTROL_FILES = setOf(
    FileSystemPromptTemplateRepository.MARKDOWN_FILE,
    FileSystemPromptTemplateRepository.METADATA_FILE,
    FileSystemPromptTemplateRepository.ORDER_FILE,
)

private val TEMPLATE_PACKAGE_FILES = setOf(
    FileSystemPromptTemplateRepository.MARKDOWN_FILE,
    FileSystemPromptTemplateRepository.METADATA_FILE,
)
