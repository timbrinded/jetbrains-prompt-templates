package dev.timbrinded.prompttemplates.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.LibraryEntry
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** The detail shown when nothing is selected. */
internal fun createEmptyDetailView(
    onNewTemplate: () -> Unit,
    onImportMarkdown: () -> Unit,
    onBrowseExamples: () -> Unit,
): JComponent {
    val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(28)
    }
    content.add(JBLabel("No prompt template selected.").apply { alignmentX = Component.LEFT_ALIGNMENT })
    content.add(Box.createVerticalStrut(JBUI.scale(10)))
    content.add(JButton("New Template").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        addActionListener { onNewTemplate() }
    })
    content.add(Box.createVerticalStrut(JBUI.scale(6)))
    content.add(JButton("Import Markdown…").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        addActionListener { onImportMarkdown() }
    })
    content.add(Box.createVerticalStrut(JBUI.scale(6)))
    content.add(JButton("Browse Examples…").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        addActionListener { onBrowseExamples() }
    })
    return content
}

/** The detail of a selected organiser folder; [relativePath] is its portable path inside the library. */
internal fun createFolderDetailView(
    folder: LibraryEntry.Folder,
    relativePath: String,
    onNewTemplate: () -> Unit,
    onNewFolder: () -> Unit,
    onImportMarkdown: () -> Unit,
): JComponent {
    val templateCount = flattenTemplates(folder.children).size
    val folderCount = flattenFolders(folder.children).size
    val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(18)
    }
    val title = JBLabel(folder.displayName).apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
    }
    val description = buildString {
        append(relativePath)
        append("\n$templateCount template${if (templateCount == 1) "" else "s"}")
        append(" · $folderCount nested folder${if (folderCount == 1) "" else "s"}")
    }
    val details = JBTextArea(description).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        accessibleContext.accessibleName = "Selected folder details"
    }
    val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        add(JButton("New Template").apply { addActionListener { onNewTemplate() } })
        add(JButton("New Folder").apply { addActionListener { onNewFolder() } })
        add(JButton("Import Markdown…").apply { addActionListener { onImportMarkdown() } })
    }
    panel.add(title, BorderLayout.NORTH)
    panel.add(details, BorderLayout.CENTER)
    panel.add(actions, BorderLayout.SOUTH)
    return panel
}

/** The detail of a template that could not be loaded. */
internal fun createLoadErrorView(error: PromptDetailState.LoadError): JComponent = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(18)
    add(JBLabel("Unable to open ${error.templateName}"), BorderLayout.NORTH)
    add(JBScrollPane(JBTextArea(error.message).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        accessibleContext.accessibleName = error.message
    }), BorderLayout.CENTER)
}
