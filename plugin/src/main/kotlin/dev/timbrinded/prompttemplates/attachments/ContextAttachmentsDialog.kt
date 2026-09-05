package dev.timbrinded.prompttemplates.attachments

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
import dev.timbrinded.prompttemplates.core.ContextAttachments
import dev.timbrinded.prompttemplates.core.MAX_ATTACHMENTS
import dev.timbrinded.prompttemplates.ui.ResponsiveActionsPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.IOException
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class ContextAttachmentsDialog(
    private val project: Project,
    service: PromptTemplatesProjectService,
) : DialogWrapper(project) {
    private val invocation = service.invocation
    private val generation = invocation.attachmentGeneration
    private val scope = service.childScope("ContextAttachmentsDialog")
    private val currentFile = FileEditorManager.getInstance(project).selectedTextEditor?.document?.let {
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)
    }
    private var pending = invocation.state.value?.attachments.orEmpty()
    private val model = DefaultListModel<CapturedAttachment>()
    private val items = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "Context attachments"
    }
    private val preview = JBTextArea().apply {
        isEditable = false
        accessibleContext.accessibleName = "Captured attachment text"
        font = JBUI.Fonts.create("Monospaced", 12)
    }
    private val provenance = JBTextArea().apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
        accessibleContext.accessibleName = "Attachment provenance"
    }
    private val summary = JBTextArea(2, 0).apply {
        isEditable = false; isOpaque = false; lineWrap = true
        accessibleContext.accessibleName = "Attachment capture summary"
    }
    private val controls = mutableListOf<JButton>()
    private var capturing = false

    init {
        title = "Add Context"
        setOKButtonText("Apply Attachments")
        init()
        Disposer.register(disposable) { scope.cancel() }
        items.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        refreshList()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
        preferredSize = JBUI.size(720, 440)
        add(JPanel(BorderLayout()).apply {
            add(JBLabel("16 items maximum · 256 KiB per item · 1 MiB total · no truncation"), BorderLayout.NORTH)
            add(JBLabel("Frozen text. Source changes do not refresh attachments. Apply updates the inspected preview."), BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(OnePixelSplitter(false, .35f).apply {
            firstComponent = JBScrollPane(items)
            secondComponent = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
                add(JBScrollPane(provenance).apply { preferredSize = JBUI.size(300, 90) }, BorderLayout.NORTH)
                add(JBScrollPane(preview), BorderLayout.CENTER)
            }
        }, BorderLayout.CENTER)
        add(ResponsiveActionsPanel().apply {
            add(control("Current File") {
                val file = currentFile
                if (file == null) setErrorText("No source text editor was selected when this dialog opened.")
                else capture(listOf(AttachmentSource.File(file.url)))
            }.apply { toolTipText = currentFile?.path ?: "No selected text editor" })
            add(control("Selected Files…") {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, true).withFileFilter { it.isInLocalFileSystem }.apply { title = "Select Context Files" }
                val chosen = FileChooser.chooseFiles(descriptor, project, null)
                if (chosen.isNotEmpty()) capture(chosen.sortedBy { it.path }.map { AttachmentSource.File(it.url) })
            })
            add(control("Git Diff…") { chooseGitDiff() })
            add(control("Refresh Selected") { items.selectedValue?.let { capture(listOf(it.source)) } })
            add(control("Remove") {
                items.selectedValue?.let { selected ->
                    pending = pending.filterNot { it.content.id == selected.content.id }
                    refreshList()
                }
            })
        }, BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = items

    override fun doOKAction() {
        if (capturing) return
        if (!invocation.setAttachments(generation, pending)) {
            setErrorText("The invocation changed. Cancel and reopen Add Context for the current template.")
            return
        }
        super.doOKAction()
    }

    private fun control(label: String, action: () -> Unit): JButton = JButton(label).apply {
        addActionListener { action() }; controls.add(this)
    }

    private fun chooseGitDiff() {
        val provider = project.getService(GitDiffCapture::class.java)
        if (provider == null) { setErrorText("Git diff capture requires the Git plugin. File attachments remain available."); return }
        val repositories = provider.repositories()
        if (repositories.isEmpty()) { setErrorText("No Git repositories are configured in this project."); return }
        val dialog = GitDiffSelectionDialog(project, repositories)
        if (dialog.showAndGet()) capture(listOf(dialog.source()))
    }

    private fun capture(sources: List<AttachmentSource>) {
        if (sources.size > MAX_ATTACHMENTS) { setErrorText("Select at most $MAX_ATTACHMENTS files. No items were added."); return }
        capturing = true
        controls.forEach { it.isEnabled = false }
        isOKActionEnabled = false
        setErrorText(null)
        summary.text = "Capturing selected context…"
        scope.launch(Dispatchers.EDT + ModalityState.current().asContextElement()) {
            try {
                val captured = sources.map { captureAttachment(project, it) }
                val merged = ContextAttachments(pending.map(CapturedAttachment::content)).with(captured.map(CapturedAttachment::content))
                val byId = (pending + captured).associateBy { it.content.id }
                pending = merged.items.map { requireNotNull(byId[it.id]) }
                refreshList(captured.lastOrNull()?.content?.id)
            } catch (failure: IllegalArgumentException) {
                setErrorText(failure.message ?: "Context capture failed. No items were changed.")
            } catch (failure: IOException) {
                setErrorText("Cannot read the selected source: ${failure.message}. No items were changed.")
            } finally {
                capturing = false
                controls.forEach { it.isEnabled = true }
                isOKActionEnabled = true
                updateSummary()
            }
        }
    }

    private fun refreshList(selectedId: String? = items.selectedValue?.content?.id) {
        model.clear()
        pending.forEach(model::addElement)
        if (pending.isNotEmpty()) items.selectedIndex = pending.indexOfFirst { it.content.id == selectedId }.coerceAtLeast(0)
        showSelected()
        updateSummary()
    }

    private fun updateSummary() {
        summary.text = "${pending.size} captured items · ${pending.sumOf { it.content.byteCount }} UTF-8 bytes · Current file: ${currentFile?.path ?: "none"}"
    }

    private fun showSelected() {
        val selected = items.selectedValue?.content
        preview.text = selected?.text.orEmpty()
        preview.caretPosition = 0
        provenance.text = selected?.let { "${it.source}\nCaptured: ${it.capturedAt}\n${it.byteCount} UTF-8 bytes. Frozen; the source may have changed." }.orEmpty()
        provenance.caretPosition = 0
    }
}

private class GitDiffSelectionDialog(project: Project, repositories: List<GitDiffRepository>) : DialogWrapper(project) {
    private val repository = ComboBox(repositories.toTypedArray()).apply {
        selectedIndex = -1
        preferredSize = Dimension(JBUI.scale(580), preferredSize.height)
    }
    private val scope = ComboBox(GitDiffScope.entries.toTypedArray()).apply { selectedIndex = -1 }

    init { title = "Capture Git Diff"; setOKButtonText("Capture Diff"); init() }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Repository:", repository)
        .addLabeledComponent("Scope:", scope)
        .addComponent(JBLabel("Tracked on-disk changes only. Unsaved buffers and untracked files are excluded."))
        .addComponent(JBLabel("HEAD is resolved on capture. Binary and submodule changes are unsupported."))
        .panel

    override fun getPreferredFocusedComponent(): JComponent = repository

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? = when {
        repository.selectedItem == null -> com.intellij.openapi.ui.ValidationInfo("Choose a repository explicitly.", repository)
        scope.selectedItem == null -> com.intellij.openapi.ui.ValidationInfo("Choose staged or unstaged changes explicitly.", scope)
        else -> null
    }

    fun source(): AttachmentSource.GitDiff = AttachmentSource.GitDiff(
        (repository.selectedItem as GitDiffRepository).root,
        scope.selectedItem as GitDiffScope,
    )
}
