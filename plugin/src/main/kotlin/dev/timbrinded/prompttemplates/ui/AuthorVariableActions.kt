package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import dev.timbrinded.prompttemplates.core.BUILT_IN_CONTEXT_KEYS
import dev.timbrinded.prompttemplates.core.BUILT_IN_CONTEXT_VARIABLES
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.SourceRange
import dev.timbrinded.prompttemplates.core.TemplateReconciler
import dev.timbrinded.prompttemplates.core.VariableDefinitionChange
import dev.timbrinded.prompttemplates.core.VariableEditorState
import dev.timbrinded.prompttemplates.core.canInsertPlaceholder
import dev.timbrinded.prompttemplates.core.userVariableKeyError
import java.lang.ref.WeakReference

/** Actions are mounted only in the author view; no global Markdown action or annotation is installed. */
internal class AuthorVariableActions(
    private val project: Project,
    private val field: EditorTextField,
    private val variables: VariableEditorState,
    private val parentDisposable: Disposable,
    private val refresh: (String?, Boolean) -> Unit,
    private val explainContext: (String) -> Unit,
    private val showError: (String) -> Unit,
) {
    private var disposed = false

    init { Disposer.register(parentDisposable) { disposed = true } }

    fun insertVariable() {
        val selection = captureSelection(required = false) ?: return
        val choices = variables.variables.filterNot { it.key in BUILT_IN_CONTEXT_KEYS }.map {
            VariableChoice(it.key, "Input", it.label)
        } + BUILT_IN_CONTEXT_VARIABLES.map { VariableChoice(it.key, "IDE context", it.explanation) }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("Insert Variable")
            .setItemChosenCallback { choice ->
                if (choice.key !in BUILT_IN_CONTEXT_KEYS && variables.variables.none { it.key == choice.key }) {
                    showError("The selected variable no longer exists.")
                } else if (insert(selection, choice.key)) {
                    if (choice.key in BUILT_IN_CONTEXT_KEYS) {
                        explainContext("${choice.key}: ${choice.explanation}")
                        field.editor?.contentComponent?.requestFocusInWindow()
                    } else {
                        explainContext("")
                        refresh(choice.key, true)
                    }
                }
            }.createPopup().showInFocusCenter()
    }

    fun extractVariable() {
        val selection = captureSelection(required = true) ?: return
        val dialog = ExtractVariableDialog(project, selection.text) { variables.variables.map(PromptVariable::key) }
        if (!dialog.showAndGet()) return
        val variable = dialog.variable()
        val keyError = userVariableKeyError(variable.key, variables.variables.map(PromptVariable::key))
        if (keyError != null) { showError(keyError); return }
        val change = VariableDefinitionChange(variables, variables.variables.size, null, variable)
        if (insert(selection, variable.key, change)) {
            explainContext("")
            refresh(variable.key, true)
        }
    }

    fun renameVariable(index: Int, newKey: String) {
        val old = variables.variables.getOrNull(index) ?: return
        if (newKey == old.key) return
        val error = userVariableKeyError(newKey, variables.variables.map(PromptVariable::key).filterNot { it == old.key })
        if (error != null) { showError(error); return }
        val renamed = TemplateReconciler().rename(field.text, old.key, newKey)
        val change = VariableDefinitionChange(variables, index, old.key, old.copy(key = newKey))
        WriteCommandAction.runWriteCommandAction(project, "Rename Prompt Variable", null, Runnable {
            registerChange(change, old.key, newKey)
            change.redo()
            field.document.setText(renamed)
        })
        refresh(newKey, true)
    }

    private fun insert(selection: AuthorSelection, key: String, change: VariableDefinitionChange? = null): Boolean {
        if (disposed) return false
        if (field.document.modificationStamp != selection.stamp) {
            showError("The author text changed. Select the text or caret position again.")
            return false
        }
        if (!canInsertPlaceholder(field.text, selection.range, key)) {
            showError("A placeholder cannot start here. Move outside an existing placeholder or escape character.")
            return false
        }
        val token = "{{$key}}"
        WriteCommandAction.runWriteCommandAction(project,
            if (change == null) "Insert Prompt Variable" else "Extract Prompt Variable", null, Runnable {
                if (change != null) { registerChange(change, null, key); change.redo() }
                field.document.replaceString(selection.range.start, selection.range.endExclusive, token)
                field.editor?.let { editor ->
                    editor.selectionModel.removeSelection()
                    editor.caretModel.moveToOffset(selection.range.start + token.length)
                }
            })
        return true
    }

    private fun captureSelection(required: Boolean): AuthorSelection? {
        val editor = field.editor?.takeUnless { it.isDisposed } ?: return null
        val selected = editor.selectionModel
        if (required && !selected.hasSelection()) {
            showError("Select text in Template Markdown before extracting a variable.")
            return null
        }
        val range = if (selected.hasSelection()) SourceRange(selected.selectionStart, selected.selectionEnd)
        else SourceRange(editor.caretModel.offset, editor.caretModel.offset)
        return AuthorSelection(range, field.document.modificationStamp, field.text.substring(range.start, range.endExclusive))
    }

    private fun registerChange(change: VariableDefinitionChange, previousKey: String?, nextKey: String) {
        // Undo history may outlive the author view. It must not retain the editor/panel through this callback.
        val owner = WeakReference(this)
        UndoManager.getInstance(project).undoableActionPerformed(VariableSchemaUndoAction(field.document, change) { redo ->
            owner.get()?.let { actions ->
                if (!actions.disposed) actions.refresh(if (redo) nextKey else previousKey, false)
            }
        })
    }
}

private data class AuthorSelection(val range: SourceRange, val stamp: Long, val text: String)

private data class VariableChoice(val key: String, val kind: String, val explanation: String) {
    override fun toString(): String = "$kind — {{$key}}: $explanation"
}

private class VariableSchemaUndoAction(
    document: Document,
    private val change: VariableDefinitionChange,
    private val refreshed: (Boolean) -> Unit,
) : BasicUndoableAction(document) {
    override fun undo() { change.undo(); refreshed(false) }
    override fun redo() { change.redo(); refreshed(true) }
}
