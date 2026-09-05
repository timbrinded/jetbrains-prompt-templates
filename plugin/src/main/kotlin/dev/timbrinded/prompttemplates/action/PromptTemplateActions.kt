package dev.timbrinded.prompttemplates.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService

class CreateTemplateFromSelectionAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.createFromSelection(event.getData(CommonDataKeys.EDITOR))
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}

class UsePromptTemplateAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.quickUse(event.getData(CommonDataKeys.EDITOR))
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}

class OpenPromptTemplatesAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val service = event.project?.service<PromptTemplatesProjectService>() ?: return
        service.rememberInvocationSource(event.getData(CommonDataKeys.EDITOR))
        service.show { it.focusSearch() }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}

class NewPromptTemplateAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.newTemplate()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}

class CopyRenderedPromptAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.copyRendered()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project
            ?.service<PromptTemplatesProjectService>()
            ?.canDeliver() == true
    }
}

class InsertRenderedPromptAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<PromptTemplatesProjectService>()?.insertRendered()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project
            ?.service<PromptTemplatesProjectService>()
            ?.canDeliver() == true
    }
}
