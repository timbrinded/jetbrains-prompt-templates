package dev.timbrinded.prompttemplates

import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import dev.timbrinded.prompttemplates.attachments.ContextAttachmentsDialog
import dev.timbrinded.prompttemplates.core.ATTACHMENTS_CONTEXT_KEY
import dev.timbrinded.prompttemplates.invocation.PromptInvocationSession
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import dev.timbrinded.prompttemplates.ui.LibraryFileWatcher
import dev.timbrinded.prompttemplates.ui.PromptTemplatesPanel
import dev.timbrinded.prompttemplates.ui.QuickUseDialog
import com.intellij.util.ui.UIUtil
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class PromptTemplatesProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private var panelReference: WeakReference<PromptTemplatesPanel>? = null
    private var quickUseDialog: QuickUseDialog? = null
    internal val invocation = PromptInvocationSession(project, coroutineScope)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val libraryChanges = changes.asSharedFlow()
    private var libraryWatcher: LibraryFileWatcher? = null

    init {
        Disposer.register(this, invocation)
        bindLibraryWatcher()
        project.messageBus.connect(this).subscribe(
            PromptTemplatesSettingsListener.TOPIC,
            PromptTemplatesSettingsListener {
                coroutineScope.launch(Dispatchers.EDT) {
                    invocation.changeLibrary()
                    bindLibraryWatcher()
                }
            },
        )
    }

    private fun bindLibraryWatcher() {
        libraryWatcher?.let(Disposer::dispose)
        libraryWatcher = LibraryFileWatcher(project, PromptTemplatesSettings.getInstance().libraryRoot, this, coroutineScope) {
            invocation.checkTemplate()
            changes.tryEmit(Unit)
        }
    }

    internal fun rememberInvocationSource(editor: Editor?) = invocation.rememberSource(editor)

    internal fun createPanel(): PromptTemplatesPanel = PromptTemplatesPanel(project).also(::attach)

    internal fun childScope(name: String): CoroutineScope = CoroutineScope(
        coroutineScope.coroutineContext +
            SupervisorJob(coroutineScope.coroutineContext[Job]) +
            CoroutineName(name),
    )

    private fun attach(panel: PromptTemplatesPanel) {
        panelReference = WeakReference(panel)
    }

    internal fun show(afterShown: (PromptTemplatesPanel) -> Unit = {}) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show {
            panelReference?.get()?.let(afterShown)
        }
    }

    fun newTemplate() = show(PromptTemplatesPanel::startNewTemplate)

    internal fun createFromSelection(editor: Editor?) {
        if (panelReference?.get()?.authorOpen == true) {
            PromptTemplatesNotifications.warning(project, "Save or cancel the open template before creating another template.")
            show()
            return
        }
        val source = (editor ?: FileEditorManager.getInstance(project).selectedTextEditor)?.takeIf { !it.isDisposed }
        val text = source?.selectionModel?.selectedText
        if (text.isNullOrEmpty()) {
            PromptTemplatesNotifications.warning(project, "Select text in an editor before creating a template from selection.")
            return
        }
        val sourceName = FileDocumentManager.getInstance().getFile(source.document)?.name ?: "selected text"
        quickUseDialog?.close(DialogWrapper.CANCEL_EXIT_CODE)
        show { panel -> panel.startTemplateFromSelection(text, sourceName) }
    }

    internal fun quickUse(editor: Editor?) {
        quickUseDialog?.let { it.toFront(); return }
        if (panelReference?.get()?.authorOpen == true) {
            PromptTemplatesNotifications.warning(project, "Finish editing the open template before using Quick Use.")
            show()
            return
        }
        val source = editor ?: FileEditorManager.getInstance(project).selectedTextEditor
        rememberInvocationSource(source)
        quickUseDialog = QuickUseDialog(project, this, source) { quickUseDialog = null }.also { it.show() }
    }

    internal fun manageAttachments() {
        if (invocation.state.value?.invocation?.referencedContext?.contains(ATTACHMENTS_CONTEXT_KEY) != true) {
            PromptTemplatesNotifications.warning(project, "Add {{ide.attachments}} in the template author editor before capturing context attachments.")
            return
        }
        ContextAttachmentsDialog(project, this).show()
    }

    fun copyRendered() = reportDelivery(invocation.copyRendered(), "Prompt copied to the clipboard.")

    fun insertRendered() = reportDelivery(invocation.insertRendered(), "Prompt inserted into the selected target.")

    fun canDeliver(): Boolean = invocation.renderedPayload() != null

    private fun reportDelivery(result: DestinationResult, successMessage: String) {
        when (result) {
            DestinationResult.Success -> PromptTemplatesNotifications.info(project, successMessage)
            is DestinationResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
        }
    }

    override fun dispose() {
        quickUseDialog?.let { dialog -> UIUtil.invokeLaterIfNeeded { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) } }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Prompt Templates"
    }
}
