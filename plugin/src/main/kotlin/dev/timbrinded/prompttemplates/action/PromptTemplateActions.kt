package dev.timbrinded.prompttemplates.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService

/** Every Prompt Templates action updates from the project and the thread-safe invocation state only. */
abstract class PromptTemplatesAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}

class CreateTemplateFromSelectionAction : PromptTemplatesAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.createFromSelection(event.getData(CommonDataKeys.EDITOR))
    }
}

class UsePromptTemplateAction : PromptTemplatesAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.quickUse(event.getData(CommonDataKeys.EDITOR))
    }
}

class OpenPromptTemplatesAction : PromptTemplatesAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val service = event.project?.service<PromptTemplatesProjectService>() ?: return
        service.rememberInvocationSource(event.getData(CommonDataKeys.EDITOR))
        service.show { it.focusSearch() }
    }
}

class NewPromptTemplateAction : PromptTemplatesAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.newTemplate()
    }
}

class CopyRenderedPromptAction : PromptTemplatesAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.copyRendered()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = canDeliver(event)
    }
}

class InsertRenderedPromptAction : PromptTemplatesAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.insertRendered()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = canDeliver(event)
    }
}

/** Without a created service there is no invocation to deliver; never create the service from an update. */
private fun canDeliver(event: AnActionEvent): Boolean =
    event.project?.getServiceIfCreated(PromptTemplatesProjectService::class.java)?.canDeliver() == true
