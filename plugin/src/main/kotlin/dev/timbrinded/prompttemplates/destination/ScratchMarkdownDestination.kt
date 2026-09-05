package dev.timbrinded.prompttemplates.destination

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project

internal object ScratchMarkdownDestination {
    fun deliver(project: Project, renderedPrompt: String): DestinationResult {
        val language = Language.findLanguageByID("Markdown") ?: PlainTextLanguage.INSTANCE
        val file = ScratchRootType.getInstance().createScratchFile(
            project, "rendered-prompt.md", language, renderedPrompt, ScratchFileService.Option.create_new_always,
        ) ?: return DestinationResult.Failure("Could not export the rendered prompt to a scratch Markdown file.")
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
        return if (editor != null) DestinationResult.Success else DestinationResult.Failure(
            "The scratch was created at ${file.path}, but its editor could not be opened. Open it from Scratches and Consoles.",
        )
    }
}
