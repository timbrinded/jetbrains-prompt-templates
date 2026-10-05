package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** The library's on-disk names and the entry rules that scanning, validation, saving and deletion share. */
internal object LibraryLayout {
    const val MARKDOWN_FILE = "prompt.md"
    const val METADATA_FILE = "prompt.meta.json"
    const val ORDER_FILE = ".prompt-templates-order.json"
    const val SAVE_JOURNAL_FILE = ".prompt-template-save.json"
    const val LOCK_FILE = ".prompt-templates.lock"

    /** Prefixes of the working directories the repository creates beside an entry it is deleting or renaming. */
    const val DELETE_SCRATCH_PREFIX = ".prompt-template-delete-"
    const val RENAME_SCRATCH_PREFIX = ".prompt-template-rename-"

    /** Prefix of the staging file an atomic write creates beside its target. */
    const val STAGE_PREFIX = ".prompt-template-stage-"

    private val RESERVED_FILE_NAMES = setOf(MARKDOWN_FILE, METADATA_FILE, ORDER_FILE, SAVE_JOURNAL_FILE, LOCK_FILE)
    private val MANAGEMENT_DIRECTORY_NAMES = setOf(".git", ".hg", ".svn", ".idea")
    private val OS_METADATA_FILES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    /** A directory with any canonical file or a save journal is a template package and a leaf of the tree. */
    fun isTemplatePackage(directory: Path): Boolean =
        Files.exists(directory.resolve(MARKDOWN_FILE), NOFOLLOW_LINKS) ||
            Files.exists(directory.resolve(METADATA_FILE), NOFOLLOW_LINKS) ||
            Files.exists(directory.resolve(SAVE_JOURNAL_FILE), NOFOLLOW_LINKS)

    /** Files a template package may contain when the template is deleted as one unit. */
    fun isTemplatePackageFileName(name: String): Boolean =
        name.equals(MARKDOWN_FILE, ignoreCase = true) ||
            name.equals(METADATA_FILE, ignoreCase = true) ||
            name.equals(SAVE_JOURNAL_FILE, ignoreCase = true) ||
            name.startsWith(STAGE_PREFIX, ignoreCase = true) ||
            name.lowercase() in OS_METADATA_FILES

    fun isReservedFileName(name: String): Boolean = RESERVED_FILE_NAMES.any { it.equals(name, ignoreCase = true) }

    fun isManagementDirectoryName(name: String): Boolean = name.lowercase() in MANAGEMENT_DIRECTORY_NAMES

    /** Working directories that an interrupted rename or deletion leaves behind. */
    fun isInterruptedWorkingDirectoryName(name: String): Boolean =
        name.startsWith(DELETE_SCRATCH_PREFIX, ignoreCase = true) ||
            name.startsWith(RENAME_SCRATCH_PREFIX, ignoreCase = true)

    fun isScratchName(name: String): Boolean =
        isInterruptedWorkingDirectoryName(name) || name.startsWith(STAGE_PREFIX, ignoreCase = true)

    /** Entries the library never shows or manages: version-control metadata and the repository's own working files. */
    fun isInternalLibraryEntryName(name: String): Boolean =
        isManagementDirectoryName(name) || isScratchName(name) || name.equals(LOCK_FILE, ignoreCase = true)

    /**
     * Whether traversal must treat [path] as a link and never descend into it. Windows reports a directory
     * junction as a directory that is not a symbolic link, so a reparse-point directory also counts unless it
     * resolves to its own location. Other platforms never report a directory as "other", so they skip the resolution.
     */
    fun isLink(path: Path, attributes: BasicFileAttributes): Boolean =
        attributes.isSymbolicLink || (attributes.isDirectory && attributes.isOther && !resolvesInPlace(path))

    fun isLink(path: Path): Boolean =
        isLink(path, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS))

    private fun resolvesInPlace(directory: Path): Boolean {
        val absolute = directory.toAbsolutePath()
        val parent = absolute.parent ?: return true
        return try {
            absolute.toRealPath() == parent.toRealPath().resolve(absolute.fileName)
        } catch (_: IOException) {
            false
        }
    }
}
