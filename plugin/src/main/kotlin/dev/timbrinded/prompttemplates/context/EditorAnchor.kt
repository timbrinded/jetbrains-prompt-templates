package dev.timbrinded.prompttemplates.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange

/** IDE handles stay in the adapter. A changed document or range needs an explicit new target. */
internal data class EditorAnchor(
    val editor: Editor,
    val modificationStamp: Long,
    val range: TextRange,
    val filePath: String?,
    val label: String,
) {
    fun isCurrent(): Boolean = !editor.isDisposed &&
        editor.document.modificationStamp == modificationStamp &&
        insertionRange(editor) == range &&
        FileDocumentManager.getInstance().getFile(editor.document).let { file ->
            file?.isValid != false && file?.path == filePath
        }

    companion object {
        fun capture(editor: Editor?): EditorAnchor? {
            if (editor == null || editor.isDisposed) return null
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            return EditorAnchor(
                editor, editor.document.modificationStamp, insertionRange(editor), file?.path,
                file?.name ?: "untitled document",
            )
        }
    }
}

private fun insertionRange(editor: Editor): TextRange = editor.selectionModel.let { selection ->
    if (selection.hasSelection()) TextRange(selection.selectionStart, selection.selectionEnd)
    else TextRange(editor.caretModel.offset, editor.caretModel.offset)
}
