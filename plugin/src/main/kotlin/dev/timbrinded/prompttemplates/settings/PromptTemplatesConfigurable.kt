package dev.timbrinded.prompttemplates.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class PromptTemplatesConfigurable : Configurable {
    private val settings = PromptTemplatesSettings.getInstance()
    private var rootPanel: JPanel? = null
    private lateinit var libraryPath: TextFieldWithBrowseButton
    private lateinit var confirmDeletion: JBCheckBox

    override fun getDisplayName(): String = "Prompt Templates"

    override fun createComponent(): JComponent {
        libraryPath = TextFieldWithBrowseButton()
        libraryPath.textField.accessibleContext.accessibleName = "Personal library directory"
        libraryPath.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Choose Prompt Template Library")
            val current = libraryPath.text.takeIf(String::isNotBlank)
                ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            FileChooser.chooseFile(descriptor, null, current)?.let { libraryPath.text = it.path }
        }

        confirmDeletion = JBCheckBox("Confirm before deleting a template")
        rootPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Personal library directory:"), libraryPath, 1, false)
            .addComponent(confirmDeletion)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return rootPanel!!
    }

    override fun isModified(): Boolean =
        libraryPath.text != settings.state.libraryPath ||
            confirmDeletion.isSelected != settings.state.confirmDeletion

    override fun apply() {
        val previousRoot = settings.libraryRoot
        settings.state.libraryPath = libraryPath.text.trim()
        settings.state.confirmDeletion = confirmDeletion.isSelected
        val currentRoot = settings.libraryRoot
        if (currentRoot != previousRoot) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(PromptTemplatesSettingsListener.TOPIC)
                .libraryRootChanged(currentRoot)
        }
    }

    override fun reset() {
        if (::libraryPath.isInitialized) {
            libraryPath.text = settings.state.libraryPath
            confirmDeletion.isSelected = settings.state.confirmDeletion
        }
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
