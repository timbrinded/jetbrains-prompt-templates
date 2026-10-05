package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object LibraryLayout {
    private val OS_METADATA_FILES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    /** Files a template package may contain when the template is deleted as one unit. */
    fun isTemplatePackageFileName(name: String): Boolean =
        name.equals(FileSystemPromptTemplateRepository.MARKDOWN_FILE, ignoreCase = true) ||
            name.equals(FileSystemPromptTemplateRepository.METADATA_FILE, ignoreCase = true) ||
            name.equals(FileSystemPromptTemplateRepository.SAVE_JOURNAL_FILE, ignoreCase = true) ||
            name.startsWith(TemplateFileStore.STAGE_PREFIX, ignoreCase = true) ||
            name.lowercase() in OS_METADATA_FILES

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
