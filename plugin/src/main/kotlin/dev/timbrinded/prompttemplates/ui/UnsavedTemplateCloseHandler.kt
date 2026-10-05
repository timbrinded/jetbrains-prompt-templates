package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService

/** Lets the user keep a changed template draft instead of losing it when its project or the IDE closes. */
internal class UnsavedTemplateCloseHandler : ProjectCloseHandler {
    override fun canClose(project: Project): Boolean =
        project.getServiceIfCreated(PromptTemplatesProjectService::class.java)?.canCloseProject() ?: true
}
