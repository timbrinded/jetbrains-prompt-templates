package dev.timbrinded.prompttemplates

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.timbrinded.prompttemplates.ui.PromptTemplatesPanel
import java.lang.ref.WeakReference

@Service(Service.Level.PROJECT)
class PromptTemplatesProjectService(private val project: Project) {
    private var panelReference: WeakReference<PromptTemplatesPanel>? = null

    fun attach(panel: PromptTemplatesPanel) {
        panelReference = WeakReference(panel)
    }

    fun show(afterShown: (PromptTemplatesPanel) -> Unit = {}) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show {
            panelReference?.get()?.let(afterShown)
        }
    }

    fun newTemplate() = show(PromptTemplatesPanel::startNewTemplate)

    fun copyRendered() = show(PromptTemplatesPanel::copyRenderedPrompt)

    fun insertRendered() = show(PromptTemplatesPanel::insertRenderedPrompt)

    fun canDeliver(): Boolean = panelReference?.get()?.hasValidRenderedPrompt() == true

    companion object {
        const val TOOL_WINDOW_ID = "Prompt Templates"
    }
}
