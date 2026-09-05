package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.invocation.InvocationPresentation
import java.nio.file.Path

internal sealed interface PromptDetailState {
    data object Empty : PromptDetailState

    data class Folder(val entry: LibraryEntry.Folder) : PromptDetailState

    data class Use(val session: InvocationPresentation) : PromptDetailState {
        val stored get() = session.invocation.stored
        val values get() = session.invocation.values
        val context get() = session.invocation.context
        val render get() = session.invocation.render
        val referencedContext get() = session.invocation.referencedContext
    }

    data class Author(val author: TemplateAuthorState) : PromptDetailState

    data class LoadError(val templateName: String, val message: String) : PromptDetailState
}

internal data class TemplateAuthorState(
    val draft: PromptTemplateDraft,
    val existing: StoredTemplate?,
    val destination: Path,
) {
    fun rebasedAsNewTemplate(newRoot: Path): TemplateAuthorState = copy(
        existing = null,
        destination = newRoot.toAbsolutePath().normalize(),
    )
}

internal class PromptToolWindowState(root: Path) {
    var librarySnapshot = LibrarySnapshot(root, emptyList())
    val bodyIndex = mutableMapOf<Path, String>()
    var detail: PromptDetailState = PromptDetailState.Empty
    var mutationInProgress = false
}
