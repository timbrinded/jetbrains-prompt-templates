package dev.timbrinded.prompttemplates.destination

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

sealed interface DestinationResult {
    data object Success : DestinationResult
    data class Failure(val message: String) : DestinationResult
}

object ClipboardDestination {
    fun deliver(renderedPrompt: String): DestinationResult {
        CopyPasteManager.getInstance().setContents(StringSelection(renderedPrompt))
        return DestinationResult.Success
    }
}

object ActiveEditorDestination {
    fun deliver(project: Project, renderedPrompt: String): DestinationResult {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?: return DestinationResult.Failure("No active text editor is available.")
        val selection = editor.selectionModel
        val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
        val end = if (selection.hasSelection()) selection.selectionEnd else editor.caretModel.offset

        WriteCommandAction.runWriteCommandAction(
            project,
            "Insert Rendered Prompt",
            null,
            Runnable {
                editor.document.replaceString(start, end, renderedPrompt)
                editor.caretModel.moveToOffset(start + renderedPrompt.length)
                selection.removeSelection()
            },
        )
        return DestinationResult.Success
    }
}

object PromptTemplatesNotifications {
    fun info(project: Project, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun error(project: Project, message: String) = notify(project, message, NotificationType.ERROR)

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Prompt Templates")
            .createNotification(message, type)
            .notify(project)
    }
}
