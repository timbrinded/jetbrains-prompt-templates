package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryIteratorException
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
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.uuid.Uuid

internal enum class LibraryDeletionMode {
    PREFER_SECURE,
    CONSERVATIVE_FALLBACK,
}

internal object LibraryTreeDeletion {
    fun manifest(
        directory: Path,
        isTemplatePackage: (Path) -> Boolean,
    ): FolderDeletionPreview {
        var folderCount = 0
        var templateCount = 0
        var fileCount = 0
        var opaqueTemplatePackage: Path? = null
        val records = mutableListOf<String>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val templatePackage = isTemplatePackage(dir)
                if (opaqueTemplatePackage == null) {
                    if (templatePackage) {
                        templateCount++
                        opaqueTemplatePackage = dir
                    } else if (dir != directory) {
                        folderCount++
                    }
                }
                records += "D\u0000${directory.relativize(dir).invariantSeparatorsPathString}\u0000$templatePackage"
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                if (dir == opaqueTemplatePackage) opaqueTemplatePackage = null
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                fileCount++
                val relative = directory.relativize(file).invariantSeparatorsPathString
                // Record identity from attributes only: deletion needs no read access to the files, and
                // reading every byte twice (preview, then the recheck under the mutation lock) does not scale.
                // Added, removed, renamed or replaced entries are still detected; a same-size rewrite inside one
                // timestamp tick on a coarse filesystem is the accepted blind spot.
                records += when {
                    Files.isSymbolicLink(file) -> "L\u0000$relative\u0000${Files.readSymbolicLink(file)}"
                    attrs.isRegularFile ->
                        "F\u0000$relative\u0000${attrs.size()}\u0000${attrs.lastModifiedTime()}\u0000${attrs.fileKey()}"
                    else -> "O\u0000$relative\u0000${attrs.size()}"
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult = throw error
        })
        val fingerprint = sha256(records.sorted().joinToString("\n").encodeToByteArray())
        return FolderDeletionPreview(
            directory = directory,
            folderCount = folderCount,
            templateCount = templateCount,
            fileCount = fileCount,
            fingerprint = fingerprint,
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
        } catch (error: DirectoryIteratorException) {
            // Directory iteration reports I/O failures as an unchecked exception; treat it like the IOException it wraps.
            throw quarantineFailure(error.cause ?: error, quarantine, target)
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
            "The remaining entry is retained at '$quarantine'. It is hidden from the library; " +
                "restore or remove it in a file manager."
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
            val candidate = parent.resolve("${FileSystemPromptTemplateRepository.DELETE_SCRATCH_PREFIX}${Uuid.random()}")
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
