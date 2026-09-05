package dev.timbrinded.prompttemplates.destination

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
import dev.timbrinded.prompttemplates.context.EditorAnchor

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

internal object SelectedEditorDestination {
    fun deliver(project: Project, target: EditorAnchor?, renderedPrompt: String): DestinationResult {
        if (target == null || !target.isCurrent()) {
            return DestinationResult.Failure("The insertion target is unavailable or changed. Select Insertion Target before inserting.")
        }
        val editor = target.editor
        val writable = WriteIntentReadAction.compute {
            !editor.isViewer && FileDocumentManager.getInstance().requestWriting(editor.document, project)
        }
        if (!writable) {
            return DestinationResult.Failure("The insertion target is read-only.")
        }
        var result: DestinationResult = DestinationResult.Success

        WriteCommandAction.runWriteCommandAction(
            project,
            "Insert Rendered Prompt",
            null,
            Runnable {
                if (!target.isCurrent()) {
                    result = DestinationResult.Failure("The insertion target changed. Select Insertion Target before inserting.")
                } else {
                    editor.document.replaceString(target.range.startOffset, target.range.endOffset, renderedPrompt)
                    editor.caretModel.moveToOffset(target.range.startOffset + renderedPrompt.length)
                    editor.selectionModel.removeSelection()
                }
            },
        )
        return result
    }
}

object PromptTemplatesNotifications {
    fun info(project: Project, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun warning(project: Project, message: String) = notify(project, message, NotificationType.WARNING)

    fun error(project: Project, message: String) = notify(project, message, NotificationType.ERROR)

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Prompt Templates")
            .createNotification(message, type)
            .notify(project)
    }
}
