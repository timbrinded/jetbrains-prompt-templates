package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class LibraryDeletionManifest(
    val preview: FolderDeletionPreview,
)

internal enum class LibraryDeletionMode {
    PREFER_SECURE,
    CONSERVATIVE_FALLBACK,
}

internal object LibraryTreeDeletion {
    fun manifest(
        directory: Path,
        isTemplatePackage: (Path) -> Boolean,
    ): LibraryDeletionManifest {
        var folderCount = 0
        var templateCount = 0
        var fileCount = 0
        val records = mutableListOf<String>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val templatePackage = isTemplatePackage(dir)
                if (templatePackage) {
                    templateCount++
                } else if (dir != directory) {
                    folderCount++
                }
                records += "D\u0000${directory.relativize(dir).toPortableString()}\u0000$templatePackage"
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                fileCount++
                val relative = directory.relativize(file).toPortableString()
                records += when {
                    Files.isSymbolicLink(file) -> "L\u0000$relative\u0000${Files.readSymbolicLink(file)}"
                    attrs.isRegularFile -> "F\u0000$relative\u0000${sha256(file)}"
                    else -> "O\u0000$relative\u0000${attrs.size()}"
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult = throw error
        })
        val fingerprint = sha256(records.sorted().joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return LibraryDeletionManifest(
            FolderDeletionPreview(
                directory = directory,
                folderCount = folderCount,
                templateCount = templateCount,
                fileCount = fileCount,
                fingerprint = fingerprint,
            ),
        )
    }

    /**
     * Deletes from a fresh directory traversal. Descendant paths from an earlier
     * fingerprint pass must never be supplied to this operation.
     */
    fun deleteTree(
        directory: Path,
        mode: LibraryDeletionMode = LibraryDeletionMode.PREFER_SECURE,
    ) {
        val target = directory.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "A managed directory must have a parent." }
        val deletedSecurely = mode == LibraryDeletionMode.PREFER_SECURE &&
            Files.newDirectoryStream(parent).use { parentStream ->
                val secureParent = parentStream as? SecureDirectoryStream<Path> ?: return@use false
                deleteSecureEntry(secureParent, target.fileName)
                true
            }
        if (!deletedSecurely) {
            deleteThroughQuarantine(target)
        }
    }

    private fun deleteSecureEntry(parent: SecureDirectoryStream<Path>, name: Path) {
        val attributes = parent.getFileAttributeView(
            name,
            BasicFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )?.readAttributes() ?: throw IOException("Unable to inspect an entry during secure deletion.")
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            parent.deleteFile(name)
            return
        }

        parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { childStream ->
            val iterator = childStream.iterator()
            while (iterator.hasNext()) {
                val childName = requireNotNull(iterator.next().fileName) {
                    "A directory entry must have a file name."
                }
                deleteSecureEntry(childStream, childName)
            }
        }
        parent.deleteDirectory(name)
    }

    private fun deleteThroughQuarantine(target: Path) {
        val parent = requireNotNull(target.parent) { "A managed directory must have a parent." }
        val quarantine = nextQuarantinePath(parent)
        try {
            Files.move(target, quarantine, ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException(
                "Safe deletion is not supported by this filesystem because an atomic quarantine move is unavailable.",
                error,
            )
        }

        try {
            requireDirectoryWithoutLinks(quarantine)
            deleteFreshEntry(quarantine, emptyList())
        } catch (error: IOException) {
            throw quarantineFailure(error, quarantine, target)
        } catch (error: SecurityException) {
            throw quarantineFailure(error, quarantine, target)
        }
    }

    private fun deleteFreshEntry(entry: Path, ancestors: List<Path>) {
        ancestors.forEach(::requireDirectoryWithoutLinks)
        val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            ancestors.forEach(::requireDirectoryWithoutLinks)
            Files.delete(entry)
            return
        }

        requireDirectoryWithoutLinks(entry)
        Files.newDirectoryStream(entry).use { stream ->
            requireDirectoryWithoutLinks(entry)
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                requireDirectoryWithoutLinks(entry)
                val childName = requireNotNull(iterator.next().fileName) {
                    "A directory entry must have a file name."
                }
                requireDirectoryWithoutLinks(entry)
                deleteFreshEntry(entry.resolve(childName), ancestors + listOf(entry))
            }
        }
        ancestors.forEach(::requireDirectoryWithoutLinks)
        requireDirectoryWithoutLinks(entry)
        Files.delete(entry)
    }

    private fun quarantineFailure(error: Throwable, quarantine: Path, target: Path): IOException {
        val restored = restoreQuarantine(quarantine, target)
        val outcome = if (restored) {
            "The remaining entry was restored to '$target'."
        } else {
            "The remaining entry is retained at '$quarantine' for recovery."
        }
        return IOException("Safe deletion did not complete. $outcome", error)
    }

    private fun requireDirectoryWithoutLinks(directory: Path) {
        val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw IOException("A library directory changed during deletion. No further entries were deleted.")
        }
    }

    private fun nextQuarantinePath(parent: Path): Path {
        while (true) {
            val candidate = parent.resolve("$QUARANTINE_PREFIX${UUID.randomUUID()}")
            if (!Files.exists(candidate, NOFOLLOW_LINKS)) return candidate
        }
    }

    private fun restoreQuarantine(quarantine: Path, target: Path): Boolean {
        if (!Files.exists(quarantine, NOFOLLOW_LINKS) || Files.exists(target, NOFOLLOW_LINKS)) return false
        return try {
            Files.move(quarantine, target, ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun Path.toPortableString(): String = iterator().asSequence().joinToString("/")

    private const val QUARANTINE_PREFIX = ".prompt-template-delete-"
}
