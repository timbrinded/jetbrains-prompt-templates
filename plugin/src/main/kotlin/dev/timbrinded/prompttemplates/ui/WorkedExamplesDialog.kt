package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.WorkedExample
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class WorkedExamplesDialog(project: Project, destination: Path, examples: List<WorkedExample>) : DialogWrapper(project) {
    private val choices = JBList(*examples.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "Worked examples"
    }
    private val markdown = textArea("Example template Markdown")
    private val inputs = textArea("Example inputs and context")
    private val output = textArea("Expected example output")
    private val introduction = textArea("Example introduction").apply {
        lineWrap = true; wrapStyleWord = true; isOpaque = false; rows = 4
        text = "Optional, local examples. All walkthrough inputs and IDE context below are mock data. " +
            "Add Example creates an editable copy; its Use view requires your own inputs and real IDE context.\nAdd to: $destination"
    }

    init {
        title = "Browse Examples"
        setOKButtonText("Add Example")
        setCancelButtonText("Close")
        init()
        choices.addListSelectionListener { if (!it.valueIsAdjusting) showExample() }
        choices.selectedIndex = 0
    }

    val selectedExample: WorkedExample get() = requireNotNull(choices.selectedValue)

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
        preferredSize = JBUI.size(780, 460)
        minimumSize = JBUI.size(480, 320)
        add(introduction, BorderLayout.NORTH)
        add(OnePixelSplitter(false, .3f).apply {
            firstComponent = JBScrollPane(choices)
            secondComponent = JBTabbedPane().apply {
                addTab("Template Markdown", JBScrollPane(markdown))
                addTab("Mock Inputs", JBScrollPane(inputs))
                addTab("Expected Output", JBScrollPane(output))
            }
        }, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = choices

    private fun showExample() {
        val example = choices.selectedValue ?: return
        markdown.text = example.template.markdown
        inputs.text = buildString {
            appendLine(example.template.metadata.description)
            appendLine("\nInputs (mock values; omitted values use authored defaults):")
            for (variable in example.template.metadata.variables) {
                fun shownValue(value: String): String = variable.options.firstOrNull { it.id == value }?.label ?: value
                appendLine("\n${variable.label} [${variable.type.name.lowercase()}, ${if (variable.required) "required" else "optional"}]")
                appendLine("Default: ${variable.defaultValue?.let(::shownValue) ?: "none"}")
                if (variable.options.isNotEmpty()) appendLine("Choices: ${variable.options.joinToString { it.label }}")
                appendLine("Mock value: ${example.sampleValues[variable.key]?.let(::shownValue) ?: "use default"}")
            }
            appendLine("\nMock IDE context:")
            if (example.sampleContext.isEmpty()) appendLine("None. This template uses supplied inputs only.")
            example.sampleContext.forEach { (key, value) -> appendLine("$key:\n$value") }
        }
        output.text = example.expectedOutput
        listOf(markdown, inputs, output).forEach { it.caretPosition = 0 }
    }

    private fun textArea(name: String): JBTextArea = JBTextArea().apply {
        isEditable = false
        accessibleContext.accessibleName = name
        font = JBUI.Fonts.create("Monospaced", 12)
    }
}
