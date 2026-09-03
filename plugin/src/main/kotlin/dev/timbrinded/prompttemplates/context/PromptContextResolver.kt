package dev.timbrinded.prompttemplates.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.timbrinded.prompttemplates.core.BUILT_IN_CONTEXT_KEYS
import dev.timbrinded.prompttemplates.core.ContextValue
import java.awt.datatransfer.DataFlavor
import java.nio.file.InvalidPathException
import java.nio.file.Path

object PromptContextResolver {
    fun resolve(project: Project): Map<String, ContextValue> {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val values = mutableMapOf<String, ContextValue>()
        values["ide.project.name"] = ContextValue.available(project.name, project.name)
        values["clipboard"] = clipboardValue()

        if (editor == null) {
            val unavailable = ContextValue.unavailable("No active text editor.")
            BUILT_IN_CONTEXT_KEYS.filter { it.startsWith("ide.") && it != "ide.project.name" }
                .forEach { values[it] = unavailable }
            return values
        }

        values.putAll(editorValues(project, editor))
        return values
    }

    private fun editorValues(project: Project, editor: Editor): Map<String, ContextValue> {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val selection = editor.selectionModel.selectedText
        val values = mutableMapOf<String, ContextValue>()

        values["ide.selection"] = if (selection.isNullOrEmpty()) {
            ContextValue.unavailable("The active editor has no selection.")
        } else {
            val lines = selection.lineSequence().count()
            ContextValue.available(selection, "$lines ${if (lines == 1) "line" else "lines"}")
        }

        if (file == null) {
            val unavailable = ContextValue.unavailable("The active editor has no file.")
            listOf("ide.file.name", "ide.file.path", "ide.file.relativePath", "ide.language")
                .forEach { values[it] = unavailable }
            return values
        }

        values["ide.file.name"] = ContextValue.available(file.name, file.name)
        values["ide.file.path"] = ContextValue.available(file.path, file.path)
        values["ide.language"] = ContextValue.available(file.fileType.description, file.fileType.description)
        values["ide.file.relativePath"] = relativePath(project, file.path)
        return values
    }

    private fun relativePath(project: Project, filePath: String): ContextValue {
        val basePath = project.basePath ?: return ContextValue.unavailable("The project has no base path.")
        return try {
            val relative = Path.of(basePath).toAbsolutePath().normalize()
                .relativize(Path.of(filePath).toAbsolutePath().normalize())
                .toString()
            ContextValue.available(relative, relative)
        } catch (_: InvalidPathException) {
            ContextValue.unavailable("The active file is outside the project.")
        } catch (_: IllegalArgumentException) {
            ContextValue.unavailable("The active file is outside the project.")
        } catch (_: SecurityException) {
            ContextValue.unavailable("The active file is outside the project.")
        }
    }

    private fun clipboardValue(): ContextValue {
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        return if (text == null) {
            ContextValue.unavailable("The clipboard does not contain text.")
        } else {
            ContextValue.available(text, "${text.length} characters")
        }
    }
}
