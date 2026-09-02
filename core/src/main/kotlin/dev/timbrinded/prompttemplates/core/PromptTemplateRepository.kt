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

    data class Failure(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>
}

data class StoredTemplate(
    val template: PromptTemplate,
    val directory: Path,
    val recoverable: Boolean = false,
)

interface PromptTemplateRepository {
    val root: Path

    fun scan(): LibrarySnapshot
    fun load(directory: Path): RepositoryResult<StoredTemplate>
    fun create(draft: PromptTemplateDraft, destinationFolder: Path = root): RepositoryResult<StoredTemplate>
    fun update(directory: Path, draft: PromptTemplateDraft): RepositoryResult<StoredTemplate>
    fun deleteTemplate(directory: Path): RepositoryResult<Unit>
    fun importMarkdown(source: Path, destinationFolder: Path = root): RepositoryResult<StoredTemplate>
    fun exportTemplateMarkdown(directory: Path, destination: Path): RepositoryResult<Path>
    fun exportRenderedMarkdown(rendered: String, destination: Path): RepositoryResult<Path>
    fun createFolder(parent: Path, name: String): RepositoryResult<Path>
    fun renameFolder(directory: Path, newName: String): RepositoryResult<Path>

    fun moveEntry(
        entry: Path,
        destinationFolder: Path,
        placement: EntryPlacement = EntryPlacement.EndOfKind,
    ): RepositoryResult<Path>

    fun previewFolderDeletion(directory: Path): RepositoryResult<FolderDeletionPreview>
    fun deleteFolder(preview: FolderDeletionPreview): RepositoryResult<Unit>

    @Deprecated("Use deleteTemplate")
    fun delete(directory: Path): RepositoryResult<Unit> = deleteTemplate(directory)
}
