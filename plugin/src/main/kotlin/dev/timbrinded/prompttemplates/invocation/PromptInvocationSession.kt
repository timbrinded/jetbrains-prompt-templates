package dev.timbrinded.prompttemplates.invocation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import dev.timbrinded.prompttemplates.attachments.CapturedAttachment
import dev.timbrinded.prompttemplates.core.ATTACHMENTS_CONTEXT_KEY
import dev.timbrinded.prompttemplates.core.ContextAttachments
import dev.timbrinded.prompttemplates.context.EditorAnchor
import dev.timbrinded.prompttemplates.context.PromptContextResolver
import dev.timbrinded.prompttemplates.core.DiagnosticSeverity
import dev.timbrinded.prompttemplates.core.FileSystemPromptTemplateRepository
import dev.timbrinded.prompttemplates.core.PromptInvocation
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.core.compatibleInvocationValues
import dev.timbrinded.prompttemplates.destination.SelectedEditorDestination
import dev.timbrinded.prompttemplates.destination.ClipboardDestination
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.ui.flattenTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class InvocationPresentation(
    val invocation: PromptInvocation,
    val insertionLabel: String,
    val capturing: Boolean = false,
    val contextChanged: Boolean = false,
    val templateProblem: String? = null,
    val attachments: List<CapturedAttachment> = emptyList(),
) {
    val deliveryProblem: String?
        get() = when {
            capturing -> "Wait for context capture to finish."
            templateProblem != null -> templateProblem
            else -> invocation.render.diagnostics.firstOrNull { it.severity == DiagnosticSeverity.ERROR }?.message
        }
}

/** The project owns one invocation. Views observe it; destinations receive only its inspected payload. */
internal class PromptInvocationSession(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val mutableState = MutableStateFlow<InvocationPresentation?>(null)
    val state = mutableState.asStateFlow()
    private val savedInputs = mutableMapOf<TemplateId, SavedInputs>()
    private var sourceAnchor: EditorAnchor? = null
    private var insertionTarget: EditorAnchor? = null
    private var rememberedSource: EditorAnchor? = null
    private var invocationLibraryRoot = PromptTemplatesSettings.getInstance().libraryRoot
    private var generation = 0
    private var sessionGeneration = 0
    private var captureJob: Job? = null
    private var templateCheckJob: Job? = null
    private var reloadPending = false
    private var disposed = false

    init {
        val editors = EditorFactory.getInstance()
        editors.eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = checkSource()
        }, this)
        editors.eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) = checkSource()
        }, this)
        editors.eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = checkSource()
        }, this)
        editors.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (sourceAnchor?.editor === event.editor) {
                    sourceAnchor = null
                    markContextChanged()
                }
                if (insertionTarget?.editor === event.editor) insertionTarget = null
                if (rememberedSource?.editor === event.editor) rememberedSource = null
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = checkSource()
        })
        CopyPasteManager.getInstance().addContentChangedListener({ _, _ ->
            if (mutableState.value?.invocation?.referencedContext?.contains("clipboard") == true) markContextChanged()
        }, this)
    }

    fun rememberSource(editor: Editor?) {
        val fileEditor = editor?.takeIf { !it.isDisposed && FileDocumentManager.getInstance().getFile(it.document) != null }
        rememberedSource = EditorAnchor.capture(fileEditor) ?: activeAnchor()
    }

    fun clearRememberedSource() {
        rememberedSource = null
    }

    fun open(stored: StoredTemplate) {
        invocationLibraryRoot = PromptTemplatesSettings.getInstance().libraryRoot
        val source = rememberedSource ?: activeAnchor()
        rememberedSource = null
        open(stored, source, selectTarget = true)
    }

    private fun open(stored: StoredTemplate, source: EditorAnchor?, selectTarget: Boolean) {
        sessionGeneration++
        templateCheckJob?.cancel()
        reloadPending = false
        rememberInputs()
        val previous = savedInputs[stored.template.id]
        val values = previous?.let {
            compatibleInvocationValues(it.variables, stored.template.metadata.variables, it.values)
        }.orEmpty()
        if (selectTarget) insertionTarget = source
        mutableState.value = InvocationPresentation(
            PromptInvocation(stored, values, emptyMap()), targetLabel(), capturing = true,
        )
        capture(source)
    }

    fun setValue(key: String, value: String) {
        val current = mutableState.value ?: return
        mutableState.value = current.copy(invocation = current.invocation.withValue(key, value))
    }

    fun resetValues() {
        val current = mutableState.value ?: return
        mutableState.value = current.copy(invocation = current.invocation.resetValues())
    }

    val attachmentGeneration: Int get() = sessionGeneration

    fun setAttachments(expectedGeneration: Int, attachments: List<CapturedAttachment>): Boolean {
        val current = mutableState.value ?: return false
        if (disposed || sessionGeneration != expectedGeneration || ATTACHMENTS_CONTEXT_KEY !in current.invocation.referencedContext) return false
        val captured = ContextAttachments(attachments.map(CapturedAttachment::content))
        mutableState.value = current.copy(
            attachments = attachments.toList(),
            invocation = PromptInvocation(current.invocation.stored, current.invocation.values,
                current.invocation.context + (ATTACHMENTS_CONTEXT_KEY to captured.contextValue())),
        )
        return true
    }

    fun refreshContext() = capture(activeAnchor())

    fun selectInsertionTarget() {
        insertionTarget = activeAnchor()
        mutableState.value = mutableState.value?.copy(insertionLabel = targetLabel())
    }

    private fun capture(source: EditorAnchor?) {
        val current = mutableState.value ?: return
        captureJob?.cancel()
        val request = ++generation
        sourceAnchor = source
        mutableState.value = current.copy(capturing = true, contextChanged = false)
        captureJob = scope.launch(Dispatchers.EDT) {
            val context = PromptContextResolver.resolve(project, current.invocation.referencedContext, source)
            if (!isCurrent(request)) return@launch
            val latest = mutableState.value ?: return@launch
            val capturedContext = if (ATTACHMENTS_CONTEXT_KEY in latest.invocation.referencedContext) {
                context + (ATTACHMENTS_CONTEXT_KEY to ContextAttachments(latest.attachments.map(CapturedAttachment::content)).contextValue())
            } else context
            mutableState.value = latest.copy(
                invocation = PromptInvocation(latest.invocation.stored, latest.invocation.values, capturedContext),
                capturing = false,
                contextChanged = latest.contextChanged || (
                    latest.invocation.referencedContext.any { it.startsWith("ide.") && it != "ide.project.name" && it != ATTACHMENTS_CONTEXT_KEY } &&
                        source?.isCurrent() == false
                    ),
            )
        }
    }

    fun renderedPayload(): String? = mutableState.value
        ?.takeIf { it.deliveryProblem == null }
        ?.invocation?.render?.renderedText

    fun copyRendered(): DestinationResult = deliver(ClipboardDestination::deliver)

    fun insertRendered(): DestinationResult = deliver { text ->
        SelectedEditorDestination.deliver(project, insertionTarget, text)
    }

    private fun deliver(destination: (String) -> DestinationResult): DestinationResult {
        val payload = renderedPayload() ?: return DestinationResult.Failure(
            mutableState.value?.deliveryProblem ?: "Choose a template first.",
        )
        return destination(payload).also { result ->
            if (result is DestinationResult.Success) {
                mutableState.value?.invocation?.stored?.template?.id?.let { id ->
                    PromptTemplatesSettings.getInstance().recordUse(id.value, invocationLibraryRoot)
                }
            }
        }
    }

    /** A move updates location only. An external edit keeps the inspected render until explicit reload. */
    fun checkTemplate(reload: Boolean = false) {
        val current = mutableState.value ?: return
        reloadPending = reloadPending || reload
        val request = sessionGeneration
        val root = PromptTemplatesSettings.getInstance().libraryRoot
        val repo = FileSystemPromptTemplateRepository(root)
        val stored = current.invocation.stored
        templateCheckJob?.cancel()
        templateCheckJob = scope.launch(Dispatchers.IO) {
            val direct = repo.load(stored.directory)
            val latest = if (direct is RepositoryResult.Success && direct.value.template.id == stored.template.id) direct else {
                val moved = flattenTemplates(repo.scan().children).firstOrNull { it.summary.id == stored.template.id }
                moved?.let { repo.load(it.directory) } ?: if (direct is RepositoryResult.Failure) direct else {
                    RepositoryResult.Failure("The template is unavailable. Restore it or choose another template.")
                }
            }
            withContext(Dispatchers.EDT) {
                if (disposed || project.isDisposed || sessionGeneration != request ||
                    root != PromptTemplatesSettings.getInstance().libraryRoot
                ) return@withContext
                val active = mutableState.value ?: return@withContext
                when (latest) {
                    is RepositoryResult.Success -> when {
                        reloadPending -> {
                            reloadPending = false
                            open(latest.value, activeAnchor(), selectTarget = false)
                        }
                        latest.value.template != active.invocation.stored.template -> {
                            mutableState.value = active.copy(templateProblem = "Template changed on disk. Reload Template to use the new version.")
                        }
                        else -> mutableState.value = active.copy(
                            invocation = PromptInvocation(latest.value, active.invocation.values, active.invocation.context),
                            templateProblem = null,
                        )
                    }
                    is RepositoryResult.Failure -> {
                        reloadPending = false
                        mutableState.value = active.copy(templateProblem = latest.message)
                    }
                }
            }
        }
    }

    fun close() {
        rememberInputs()
        generation++
        sessionGeneration++
        captureJob?.cancel()
        templateCheckJob?.cancel()
        reloadPending = false
        sourceAnchor = null
        insertionTarget = null
        mutableState.value = null
    }

    fun changeLibrary() {
        close()
        savedInputs.clear()
        rememberedSource = null
    }

    private fun rememberInputs() {
        mutableState.value?.invocation?.let { invocation ->
            savedInputs[invocation.stored.template.id] = SavedInputs(invocation.stored.template.metadata.variables, invocation.values)
        }
    }

    private fun checkSource() = UIUtil.invokeLaterIfNeeded {
        if (disposed) return@invokeLaterIfNeeded
        val current = mutableState.value ?: return@invokeLaterIfNeeded
        if (current.invocation.referencedContext.none { it.startsWith("ide.") && it != "ide.project.name" && it != ATTACHMENTS_CONTEXT_KEY }) return@invokeLaterIfNeeded
        val source = sourceAnchor
        if (source == null || !source.isCurrent() || selectedEditor() !== source.editor) markContextChanged()
    }

    private fun markContextChanged() = UIUtil.invokeLaterIfNeeded {
        if (!disposed) mutableState.value = mutableState.value?.copy(contextChanged = true)
    }

    private fun selectedEditor(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor
    private fun activeAnchor(): EditorAnchor? = EditorAnchor.capture(selectedEditor())
    private fun targetLabel(): String = insertionTarget?.let { "Insert into ${it.label}" } ?: "Insert…"
    private fun isCurrent(request: Int): Boolean = !disposed && !project.isDisposed && generation == request

    override fun dispose() {
        disposed = true
        changeLibrary()
    }

    private data class SavedInputs(val variables: List<PromptVariable>, val values: Map<String, String>)
}
