package dev.timbrinded.prompttemplates.context

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.timbrinded.prompttemplates.core.ATTACHMENTS_CONTEXT_KEY
import dev.timbrinded.prompttemplates.core.ContextAttachments
import dev.timbrinded.prompttemplates.core.ContextStatus
import dev.timbrinded.prompttemplates.core.ContextValue
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PromptContextResolver {
    suspend fun resolve(project: Project, requested: Set<String>, source: EditorAnchor?): Map<String, ContextValue> {
        // Clipboard access stays on the UI dispatcher; editor text is copied in a cancellable background read action.
        val clipboard = if ("clipboard" in requested) withContext(Dispatchers.EDT) { clipboardValue() } else null
        return readAction {
            requested.associateWith { key ->
                when (key) {
                    "clipboard" -> requireNotNull(clipboard)
                    ATTACHMENTS_CONTEXT_KEY -> ContextAttachments().contextValue()
                    "ide.project.name" -> ContextValue.available(project.name, project.name)
                    else -> editorValue(project, source, key)
                }
            }
        }
    }

    private fun editorValue(project: Project, source: EditorAnchor?, key: String): ContextValue {
        if (key !in EDITOR_KEYS) return ContextValue(ContextStatus.UNKNOWN)
        if (source == null || source.editor.isDisposed) return ContextValue.unavailable("No source text editor. Open a file and Refresh Context.")
        val document = source.editor.document
        if (document.modificationStamp != source.modificationStamp) {
            return ContextValue.unavailable("The source changed during capture. Refresh Context.")
        }
        val file = FileDocumentManager.getInstance().getFile(document)
        if (key == "ide.selection") {
            if (source.range.isEmpty) return ContextValue.unavailable("The source editor has no selection. Select text and Refresh Context.")
            val firstLine = document.getLineNumber(source.range.startOffset) + 1
            val lastLine = document.getLineNumber(source.range.endOffset - 1) + 1
            val lines = if (firstLine == lastLine) "line $firstLine" else "lines $firstLine–$lastLine"
            return ContextValue.available(document.getText(source.range), "${source.filePath ?: source.label}: $lines")
        }
        if (file == null || !file.isValid) return ContextValue.unavailable("The source editor has no available file.")
        return when (key) {
            "ide.file.name" -> ContextValue.available(file.name, file.path)
            "ide.file.path" -> ContextValue.available(file.path, file.path)
            "ide.language" -> ContextValue.available(file.fileType.description, "${file.path}: ${file.fileType.description}")
            "ide.file.relativePath" -> relativePath(project, file.path)
            else -> ContextValue(ContextStatus.UNKNOWN)
        }
    }

    private fun relativePath(project: Project, filePath: String): ContextValue {
        val basePath = project.basePath ?: return ContextValue.unavailable("The project has no base path.")
        return try {
            val relative = Path.of(basePath).toAbsolutePath().normalize()
                .relativize(Path.of(filePath).toAbsolutePath().normalize()).toString()
            ContextValue.available(relative, filePath)
        } catch (_: IllegalArgumentException) {
            ContextValue.unavailable("The source file has no project-relative path.")
        } catch (_: SecurityException) {
            ContextValue.unavailable("The source file has no project-relative path.")
        }
    }

    private fun clipboardValue(): ContextValue {
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        return if (text == null) ContextValue.unavailable("The clipboard does not contain text. Copy text and Refresh Context.")
        else ContextValue.available(text, "${text.length} captured characters")
    }

    private val EDITOR_KEYS = setOf("ide.selection", "ide.file.name", "ide.file.path", "ide.file.relativePath", "ide.language")
}
