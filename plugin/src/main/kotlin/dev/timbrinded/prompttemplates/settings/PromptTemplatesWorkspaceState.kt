package dev.timbrinded.prompttemplates.settings

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Property

/**
 * Tool-window state that belongs to one project window: which organiser folders are expanded and which
 * template is selected. Kept in the project's workspace file so two open projects do not overwrite each
 * other's tree, unlike the application-level [PromptTemplatesSettings].
 */
@Service(Service.Level.PROJECT)
@State(name = "PromptTemplatesWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PromptTemplatesWorkspaceState :
    SerializablePersistentStateComponent<PromptTemplatesWorkspaceState.WorkspaceState>(WorkspaceState()) {
    data class WorkspaceState(
        @JvmField val expandedFolderPaths: List<String> = emptyList(),
        @JvmField @field:Property val selectedTemplateId: String? = null,
    )

    var selectedTemplateId: String?
        get() = state.selectedTemplateId
        set(value) {
            updateState { it.copy(selectedTemplateId = value) }
        }

    val expandedFolderPaths: List<String>
        get() = state.expandedFolderPaths

    fun replaceExpandedFolderPaths(paths: Collection<String>) {
        updateState { it.copy(expandedFolderPaths = paths.toList()) }
    }

    companion object {
        fun getInstance(project: Project): PromptTemplatesWorkspaceState = project.service()
    }
}
