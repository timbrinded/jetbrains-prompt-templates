package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSearchEntry
import dev.timbrinded.prompttemplates.core.referencedUserVariables
import dev.timbrinded.prompttemplates.core.requestedContextKeys
import java.nio.file.Path

internal data class QuickUseCandidate(val search: TemplateSearchEntry, val requirements: String) {
    override fun toString(): String = "${search.summary.name} — ${search.relativePath} · $requirements"
}

internal data class QuickUseCatalog(val candidates: List<QuickUseCandidate>, val diagnostic: String?)

internal fun loadQuickUseCatalog(root: Path): QuickUseCatalog {
    val repository = FileSystemPromptTemplateRepository(root)
    val snapshot = repository.scan()
    val candidates = flattenTemplates(snapshot.children).mapNotNull { entry ->
        if (entry.summary.health != TemplateHealth.HEALTHY) return@mapNotNull null
        val stored = (repository.load(entry.directory) as? RepositoryResult.Success)?.value ?: return@mapNotNull null
        val inputs = referencedUserVariables(stored.template)
        val context = requestedContextKeys(stored.template)
        QuickUseCandidate(
            TemplateSearchEntry(entry.summary, entry.relativeDirectory.toString(), stored.template.markdown),
            "${inputs.size} inputs (${inputs.count { it.required }} required), ${context.size} context",
        )
    }
    return QuickUseCatalog(candidates, snapshot.diagnostic)
}

internal fun loadQuickUseSelection(root: Path, candidate: QuickUseCandidate): RepositoryResult<StoredTemplate> {
    val repository = FileSystemPromptTemplateRepository(root)
    val direct = repository.load(candidate.search.summary.directory)
    if (direct is RepositoryResult.Success && direct.value.template.id == candidate.search.summary.id) return direct
    val moved = flattenTemplates(repository.scan().children)
        .firstOrNull { it.summary.id == candidate.search.summary.id }
    return moved?.let { repository.load(it.directory) }
        ?: RepositoryResult.Failure("This template is no longer available. Choose another template.")
}
