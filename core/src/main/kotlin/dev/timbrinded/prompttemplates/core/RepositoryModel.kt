package dev.timbrinded.prompttemplates.core

import java.nio.file.Path

enum class TemplateHealth { HEALTHY, RECOVERABLE, BROKEN }

data class TemplateSummary(
    val id: TemplateId?,
    val name: String,
    val description: String?,
    val tags: List<String>,
    val directory: Path,
    val health: TemplateHealth,
    val diagnostic: String? = null,
)

data class LibrarySnapshot(
    val root: Path,
    val children: List<LibraryEntry>,
    val diagnostic: String? = null,
)

sealed interface LibraryEntry {
    val directory: Path
    val relativeDirectory: Path
    val displayName: String

    data class Folder(
        override val directory: Path,
        override val relativeDirectory: Path,
        override val displayName: String,
        val children: List<LibraryEntry>,
        val diagnostic: String? = null,
    ) : LibraryEntry

    data class Template(
        val summary: TemplateSummary,
        override val relativeDirectory: Path,
    ) : LibraryEntry {
        override val directory: Path get() = summary.directory
        override val displayName: String get() = summary.name
    }
}

sealed interface EntryPlacement {
    data object EndOfKind : EntryPlacement
    data class Before(val sibling: Path) : EntryPlacement
    data class After(val sibling: Path) : EntryPlacement
}

data class FolderDeletionPreview(
    val directory: Path,
    val folderCount: Int,
    val templateCount: Int,
    val fileCount: Int,
    val fingerprint: String,
)

sealed interface RepositoryResult<out T> {
    data class Success<T>(
        val value: T,
        val warnings: List<String> = emptyList(),
    ) : RepositoryResult<T>

    open class Failure(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>

    data class Conflict(val current: StoredTemplate) : Failure(
        "The template changed on disk. Your draft is unchanged. Save again to review the current version.",
    )
}

data class StoredTemplate(
    val template: PromptTemplate,
    val directory: Path,
    val recoverable: Boolean = false,
    val revision: TemplateRevision? = null,
)
