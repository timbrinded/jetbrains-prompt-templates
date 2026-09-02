package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.ContextValue
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.RenderResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateId
import java.nio.file.Path

internal sealed interface PromptDetailState {
    data object Empty : PromptDetailState

    data class Folder(val entry: LibraryEntry.Folder) : PromptDetailState

    data class Use(
        val stored: StoredTemplate,
        val target: TemplateDetailTarget,
        val values: MutableMap<String, String>,
        val context: Map<String, ContextValue>,
        val render: RenderResult,
        val referencedContext: List<String>,
    ) : PromptDetailState

    data class Author(val author: TemplateAuthorState) : PromptDetailState

    data class LoadError(val templateName: String, val message: String) : PromptDetailState
}

internal data class TemplateAuthorState(
    val draft: PromptTemplateDraft,
    val existing: StoredTemplate?,
    val existingTarget: TemplateDetailTarget?,
    val selectionBefore: LibrarySelectionKey?,
    val destination: Path,
) {
    fun rebasedAsNewTemplate(newRoot: Path): TemplateAuthorState = copy(
        existing = null,
        existingTarget = null,
        selectionBefore = null,
        destination = newRoot.toAbsolutePath().normalize(),
    )
}

internal class PromptToolWindowState(root: Path) {
    var librarySnapshot = LibrarySnapshot(root, emptyList())
    val bodyIndex = mutableMapOf<Path, String>()
    val sessionValues = mutableMapOf<TemplateId, MutableMap<String, String>>()
    var detail: PromptDetailState = PromptDetailState.Empty
    var mutationInProgress = false
}
