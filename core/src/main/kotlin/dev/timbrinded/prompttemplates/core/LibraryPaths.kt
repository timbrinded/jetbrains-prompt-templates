package dev.timbrinded.prompttemplates.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.name

/** The portable file-name stem the library derives from [name]; exports suggest the same stem. */
fun portableSlug(name: String): String = LibraryPaths.slug(name)

/**
 * Validates paths that callers supply against the managed library hierarchy. Accepted paths are absolute,
 * normalised, inside the root, free of links and internal entries, and never below a template package.
 */
internal class LibraryPaths(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()

    fun requireRoot(): Path {
        require(Files.exists(root, NOFOLLOW_LINKS)) { "The template library does not exist." }
        require(Files.isDirectory(root)) { "The template library path is not a directory." }
        root.toRealPath()
        return root
    }

    fun ensureRoot(): Path {
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectories(root)
        return requireRoot()
    }

    fun requireTemplateDirectory(directory: Path): Path {
        val safeDirectory = requireExistingManagedDirectory(directory, allowRoot = false)
        require(LibraryLayout.isTemplatePackage(safeDirectory)) {
            "The selected entry is an organiser folder, not a template."
        }
        return safeDirectory
    }

    fun requireOrganiserFolder(directory: Path, createRoot: Boolean = false): Path {
        val libraryRoot = if (createRoot) ensureRoot() else requireRoot()
        val normalDirectory = directory.toAbsolutePath().normalize()
        require(normalDirectory == libraryRoot || normalDirectory.startsWith(libraryRoot)) {
            "Folder must be inside the template library."
        }
        val safeDirectory = requireExistingManagedDirectory(normalDirectory, allowRoot = true)
        if (safeDirectory != libraryRoot) {
            require(!LibraryLayout.isTemplatePackage(safeDirectory)) { "Templates cannot contain organiser folders." }
        }
        return safeDirectory
    }

    fun requireEntry(entry: Path): Path = requireExistingManagedDirectory(entry, allowRoot = false)

    private fun requireExistingManagedDirectory(path: Path, allowRoot: Boolean): Path {
        val libraryRoot = requireRoot()
        val normalPath = path.toAbsolutePath().normalize()
        require(normalPath.startsWith(libraryRoot) && (allowRoot || normalPath != libraryRoot)) {
            "Entry must be inside the template library."
        }
        require(Files.exists(normalPath, NOFOLLOW_LINKS)) { "Library entry does not exist." }
        require(Files.isDirectory(normalPath)) { "Library entry is not a directory." }

        var current = libraryRoot
        libraryRoot.relativize(normalPath).forEach { segment ->
            val segmentName = segment.name
            if (segmentName.isEmpty()) return@forEach
            require(!LibraryLayout.isInternalLibraryEntryName(segmentName)) {
                "IDE metadata, version-control and library working directories are not part of the template library."
            }
            current = current.resolve(segment)
            require(!LibraryLayout.isLink(current)) { "Symbolic links and directory junctions are not supported." }
            require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "Library path is not a directory." }
            require(current == normalPath || !LibraryLayout.isTemplatePackage(current)) {
                "Entries inside a template package are not part of the managed library hierarchy."
            }
        }
        val realRoot = libraryRoot.toRealPath()
        val realPath = normalPath.toRealPath()
        require(realPath.startsWith(realRoot)) { "Entry resolves outside the template library." }
        return normalPath
    }

    companion object {
        private val INVALID_FOLDER_NAME_CHARACTERS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        private val WINDOWS_DEVICE_NAMES = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }
        private const val MAX_NAME_BYTES = 255

        /** Generated directory names stay short so nested paths remain well inside platform limits. */
        private const val MAX_SLUG_LENGTH = 64

        fun requireFolderName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "Folder name is required." }
            require(trimmed == name) { "Folder names cannot start or end with whitespace." }
            require(trimmed != "." && trimmed != "..") { "Folder name is not valid." }
            require(trimmed.none { it.code < 32 || it in INVALID_FOLDER_NAME_CHARACTERS }) {
                "Folder name contains a character that is not portable across supported systems."
            }
            require(!trimmed.endsWith('.')) { "Folder names cannot end with a period." }
            require(trimmed.encodeToByteArray().size <= MAX_NAME_BYTES) { "Folder name is too long." }
            require(!isWindowsDeviceName(trimmed)) { "'$trimmed' is a reserved device name on Windows." }
            require(!LibraryLayout.isReservedFileName(trimmed)) { "'$trimmed' is reserved by the prompt-template library." }
            require(!LibraryLayout.isManagementDirectoryName(trimmed)) {
                "'$trimmed' is reserved for IDE or version-control metadata."
            }
            require(!LibraryLayout.isScratchName(trimmed)) {
                "'$trimmed' uses a prefix reserved for the library's working directories."
            }
            return trimmed
        }

        /** A portable directory name for a template; it is never shown, so collisions only need a suffix. */
        fun slug(name: String): String {
            val slug = name
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .take(MAX_SLUG_LENGTH)
                .trim('-')
                .ifEmpty { "prompt-template" }
            return if (isWindowsDeviceName(slug)) "$slug-template" else slug
        }

        fun nextAvailableDirectory(parent: Path, base: String): Path {
            var candidate = parent.resolve(base)
            var suffix = 2
            while (Files.exists(candidate, NOFOLLOW_LINKS)) {
                candidate = parent.resolve("$base-$suffix")
                suffix++
            }
            return candidate
        }

        /** Windows reserves these stems with any extension, such as `NUL.txt`. */
        private fun isWindowsDeviceName(name: String): Boolean =
            name.substringBefore('.').trimEnd(' ').lowercase() in WINDOWS_DEVICE_NAMES
    }
}
