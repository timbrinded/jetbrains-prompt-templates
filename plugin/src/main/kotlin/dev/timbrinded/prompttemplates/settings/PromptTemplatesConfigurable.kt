package dev.timbrinded.prompttemplates.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

class PromptTemplatesConfigurable : BoundConfigurable("Prompt Templates") {
    private val settings = PromptTemplatesSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row("Personal library directory:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Choose Prompt Template Library"),
            )
                .bindText(settings::libraryPath)
                .align(AlignX.FILL)
                .applyToComponent {
                    textField.accessibleContext.accessibleName = "Personal library directory"
                }
        }
        row {
            checkBox("Confirm before deleting a template")
                .bindSelected(settings::confirmDeletion)
        }
    }

    override fun apply() {
        val previousRoot = settings.libraryRoot
        super.apply()
        notifyLibraryRootChange(previousRoot, settings.libraryRoot)
    }

    private fun notifyLibraryRootChange(previousRoot: Path, currentRoot: Path) {
        if (currentRoot == previousRoot) return
        ApplicationManager.getApplication().messageBus
            .syncPublisher(PromptTemplatesSettingsListener.TOPIC)
            .libraryRootChanged(currentRoot)
    }
}
