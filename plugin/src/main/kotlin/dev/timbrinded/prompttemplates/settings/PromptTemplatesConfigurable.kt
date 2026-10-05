package dev.timbrinded.prompttemplates.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.InvalidPathException
import java.nio.file.Path

class PromptTemplatesConfigurable : BoundConfigurable("Prompt Templates") {
    private val settings = PromptTemplatesSettings.getInstance()
    private var settingsPanel: DialogPanel? = null

    override fun createPanel(): DialogPanel = panel {
        row("Personal library directory:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Choose Prompt Template Library"),
            )
                .bindText({ settings.libraryPath }, { settings.libraryPath = normalizeLibraryPathInput(it) })
                .validationOnApply { field -> libraryPathError(field.text)?.let { message -> error(message) } }
                .align(AlignX.FILL)
                .applyToComponent {
                    textField.accessibleContext.accessibleName = "Personal library directory"
                }
        }
        row {
            checkBox("Confirm before deleting a template")
                .bindSelected(settings::confirmDeletion)
        }
    }.also { settingsPanel = it }

    override fun apply() {
        // The Settings dialog does not run apply-time validation for configurables, so refuse here.
        settingsPanel?.validateAll()?.firstOrNull()?.let { problem -> throw ConfigurationException(problem.message) }
        // Resolve the previous root without the throwing Path parser, so an unparseable stored path can be replaced.
        val previousRoot = libraryRootOf(settings.libraryPath)
        super.apply()
        // Show the stored form of a quoted or padded path, so the page does not stay modified.
        reset()
        notifyLibraryRootChange(previousRoot, settings.libraryRoot)
    }

    override fun disposeUIResources() {
        settingsPanel = null
        super.disposeUIResources()
    }

    private fun notifyLibraryRootChange(previousRoot: Path, currentRoot: Path) {
        if (currentRoot == previousRoot) return
        ApplicationManager.getApplication().messageBus
            .syncPublisher(PromptTemplatesSettingsListener.TOPIC)
            .libraryRootChanged(currentRoot)
    }
}

/** Accepts the forms a pasted path commonly takes, such as Windows Explorer's quoted "Copy as path". */
internal fun normalizeLibraryPathInput(text: String): String {
    val trimmed = text.trim()
    val unquoted = if (trimmed.length >= 2 && trimmed.first() == trimmed.last() && trimmed.first() in "\"'") {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
    return unquoted.trim()
}

/** A blank path selects the default library; anything else must parse as an absolute path. */
internal fun libraryPathError(text: String): String? {
    val path = normalizeLibraryPathInput(text).ifEmpty { return null }
    val parsed = try {
        Path.of(path)
    } catch (_: InvalidPathException) {
        return "Enter a valid directory path."
    }
    return if (parsed.isAbsolute) null else "Enter an absolute directory path."
}
