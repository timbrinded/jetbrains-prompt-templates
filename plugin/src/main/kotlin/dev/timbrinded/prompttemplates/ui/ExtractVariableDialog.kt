package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.defaultVariableLabel
import dev.timbrinded.prompttemplates.core.userVariableKeyError
import javax.swing.JComponent

internal class ExtractVariableDialog(
    project: Project,
    private val selectedText: String,
    private val existingKeys: () -> Collection<String>,
) : DialogWrapper(project) {
    private val key = JBTextField()
    private val type = ComboBox(arrayOf(PromptVariableType.TEXT, PromptVariableType.MULTILINE)).apply {
        selectedItem = if ('\n' in selectedText || '\r' in selectedText) PromptVariableType.MULTILINE else PromptVariableType.TEXT
    }
    private val retainDefault = JBCheckBox("Use selected text as authored default", false)

    init {
        title = "Extract as Variable"
        setOKButtonText("Extract")
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Variable key:", key)
        .addLabeledComponent("Input type:", type)
        .addComponent(retainDefault)
        .addLabeledComponent("Selected text:", JBScrollPane(JBTextArea(selectedText, 4, 36).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            accessibleContext.accessibleName = "Selected author text"
        }), true)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = key

    override fun doValidate(): ValidationInfo? = userVariableKeyError(key.text.trim(), existingKeys())
        ?.let { ValidationInfo(it, key) }

    fun variable(): PromptVariable {
        val name = key.text.trim()
        return PromptVariable(name, defaultVariableLabel(name),
            type = type.selectedItem as PromptVariableType,
            defaultValue = selectedText.takeIf { retainDefault.isSelected })
    }
}
