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
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.invocation.PromptInvocationSession
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.destination.PromptTemplatesNotifications
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import dev.timbrinded.prompttemplates.ui.LibraryFileWatcher
import dev.timbrinded.prompttemplates.ui.PromptTemplatesPanel
import dev.timbrinded.prompttemplates.ui.QuickUseDialog
import dev.timbrinded.prompttemplates.ui.hasLibraryRootChanged
import com.intellij.util.ui.UIUtil
import java.lang.ref.WeakReference
import java.nio.file.Path
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class PromptTemplatesProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private var panelReference: WeakReference<PromptTemplatesPanel>? = null
    private var quickUseDialog: QuickUseDialog? = null
    internal val invocation = PromptInvocationSession(project, coroutineScope)
    // Collectors run on the EDT and keep up; the buffer only absorbs bursts from one EDT cycle.
    private val changes = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 16)
    internal val libraryChanges = changes.asSharedFlow()
    @Volatile
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
        libraryWatcher = LibraryFileWatcher(PromptTemplatesSettings.getInstance().libraryRoot, this, coroutineScope) {
            reportLibraryChange(LibraryChange.EXTERNAL)
        }
    }

    /**
     * Runs one of the plugin's own writes to the library at [root]. The watcher does not report it, so a successful
     * write is reported here once: the invocation follows a moved or edited template and other views reload.
     */
    internal suspend fun <T> writeLibrary(root: Path, write: () -> RepositoryResult<T>): RepositoryResult<T> {
        val watcher = libraryWatcher?.takeUnless { hasLibraryRootChanged(it.root, root) }
        val result = watcher?.ownWrite(write) ?: withContext(Dispatchers.IO) { write() }
        if (result is RepositoryResult.Success) withContext(Dispatchers.EDT) { reportLibraryChange(LibraryChange.PLUGIN) }
        return result
    }

    private fun reportLibraryChange(change: LibraryChange) {
        invocation.checkTemplate()
        changes.tryEmit(change)
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

    /** Asks about a changed author draft before the project closes; keeping it cancels the close and shows it. */
    internal fun canCloseProject(): Boolean {
        val panel = panelReference?.get() ?: return true
        if (panel.confirmCloseWithAuthorDraft()) return true
        show()
        return false
    }

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

    fun copyRendered(): DestinationResult = reportDelivery(invocation.copyRendered(), "Prompt copied to the clipboard.")

    fun insertRendered(): DestinationResult =
        reportDelivery(invocation.insertRendered(), "Prompt inserted into the selected target.")

    fun openRenderedScratch(): DestinationResult =
        reportDelivery(invocation.openRenderedScratch(), "Rendered prompt exported to a local scratch file.")

    fun canDeliver(): Boolean = invocation.renderedPayload() != null

    private fun reportDelivery(result: DestinationResult, successMessage: String): DestinationResult {
        when (result) {
            DestinationResult.Success -> PromptTemplatesNotifications.info(project, successMessage)
            is DestinationResult.Failure -> PromptTemplatesNotifications.error(project, result.message)
        }
        return result
    }

    override fun dispose() {
        quickUseDialog?.let { dialog -> UIUtil.invokeLaterIfNeeded { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) } }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Prompt Templates"
    }
}

/** Who changed the library: another process or window ([EXTERNAL]), or this window's own write ([PLUGIN]). */
internal enum class LibraryChange { EXTERNAL, PLUGIN }
