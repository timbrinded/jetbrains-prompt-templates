package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import dev.timbrinded.prompttemplates.core.PromptTemplateDraft
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Shows the disk version whose revision the subsequent update must still match. */
internal class TemplateOverwriteDialog(
    project: Project,
    private val current: StoredTemplate,
    private val draft: PromptTemplateDraft,
) : DialogWrapper(project) {
    init {
        title = "Prompt Template Changed"
        setOKButtonText("Overwrite with Draft")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JBLabel("The template changed on disk. Review both versions before replacing it."), BorderLayout.NORTH)
        add(JPanel(GridLayout(1, 2, 8, 0)).apply {
            val codec = TemplateMetadataCodec()
            add(versionPanel("Current files on disk", current.template.markdown, codec.encode(current.template.metadata)))
            add(versionPanel("Your draft", draft.markdown, codec.encode(draft.toTemplate().metadata)))
        }, BorderLayout.CENTER)
        preferredSize = Dimension(850, 480)
    }

    private fun versionPanel(label: String, markdown: String, metadata: String) = JPanel(BorderLayout(0, 4)).apply {
        add(JBLabel(label), BorderLayout.NORTH)
        add(JBScrollPane(JBTextArea("$markdown\n\n--- prompt.meta.json ---\n$metadata").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
            accessibleContext.accessibleName = label
        }), BorderLayout.CENTER)
    }
}
