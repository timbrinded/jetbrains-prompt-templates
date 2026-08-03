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

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>
}

data class StoredTemplate(
    val template: PromptTemplate,
    val directory: Path,
    val recoverable: Boolean = false,
)

interface PromptTemplateRepository {
    val root: Path

    fun scan(): List<TemplateSummary>
    fun load(directory: Path): RepositoryResult<StoredTemplate>
    fun create(draft: PromptTemplateDraft): RepositoryResult<StoredTemplate>
    fun update(directory: Path, draft: PromptTemplateDraft): RepositoryResult<StoredTemplate>
    fun delete(directory: Path): RepositoryResult<Unit>
    fun importMarkdown(source: Path): RepositoryResult<StoredTemplate>
    fun exportTemplateMarkdown(directory: Path, destination: Path): RepositoryResult<Path>
    fun exportRenderedMarkdown(rendered: String, destination: Path): RepositoryResult<Path>
}
