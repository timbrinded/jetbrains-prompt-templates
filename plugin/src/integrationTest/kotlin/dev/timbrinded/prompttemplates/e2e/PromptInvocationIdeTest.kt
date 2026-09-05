package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import kotlin.io.path.writeText
import kotlin.io.path.moveTo
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor

class PromptInvocationIdeTest {
    @Test
    fun `library changes clear use values and preserve an open author draft`() {
        val harness = StarterHarness.create("library-invocation")
        val originalRoot = harness.workspace.library
        val otherRoot = harness.workspace.root.resolve("other-library").createDirectories()
        for ((root, name) in listOf(originalRoot to "Original", otherRoot to "Other")) {
            val template = TestLibrary(root).createTemplate("review", name, "c7b3a032-cbe2-49e8-a49c-3b2edc7c3b8c")
            template.resolve("prompt.md").writeText("{{goal}}: {{clipboard}}")
            template.resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
                id = "c7b3a032-cbe2-49e8-a49c-3b2edc7c3b8c", name = name,
                variables = listOf(PromptVariable("goal", "Goal", defaultValue = "Review")),
            )))
        }
        harness.run { ui ->
            copyToClipboard("original")
            ui.open().selectTemplate("Original")
            ideFrame().textField { byAccessibleName("Goal") }.waitFound(30.seconds).text = "Explain"
            waitFor("entered values update the session", 30.seconds) { ui.renderedText() == "Explain: original" }
            ui.changeLibrary(otherRoot)
            ui.waitForPath("Other")
            ideFrame().button("Copy Prompt").waitNotFound(30.seconds)
            copyToClipboard("other")
            ui.selectTemplate("Other")
            waitFor("new library starts with authored defaults", 30.seconds) { ui.renderedText() == "Review: other" }
            ui.clickButton("Edit")
            ideFrame().textField { and(byClass("JBTextField"), byAccessibleName("Name:")) }.waitFound(30.seconds).text = "Unsaved draft"
            ui.changeLibrary(originalRoot)
            ui.waitForPath("Original")
            assertEquals("Unsaved draft", ideFrame().textField { and(byClass("JBTextField"), byAccessibleName("Name:")) }.waitFound(30.seconds).text)
            ideFrame().button("Save Template").waitFound(30.seconds)
        }
    }

    @Test
    fun `copy preserves the inspected clipboard snapshot on repeated delivery`() {
        val harness = StarterHarness.create("clipboard-snapshot")
        val directory = harness.workspace.templates.createTemplate(
            "clipboard-review", "Clipboard review", "e65b06ce-ce62-40f6-a9aa-ae264803cc5c",
        )
        directory.resolve("prompt.md").writeText("Review: {{clipboard}}")
        directory.resolve("prompt.meta.json").writeText(
            TemplateMetadataCodec().encode(
                TemplateMetadata(id = "e65b06ce-ce62-40f6-a9aa-ae264803cc5c", name = "Clipboard review"),
            ),
        )
        harness.run { ui ->
            val clipboard = utility(IdeClipboard::class).getInstance()
            val originalClipboard = clipboard.getContents(DataFlavor.stringFlavor)
            try {
                copyToClipboard("abc")
                ui.open().selectTemplate("Clipboard review")
                waitFor("preview uses captured clipboard", 30.seconds) { ui.renderedText() == "Review: abc" }
                repeat(2) {
                    ui.clickButton("Copy Prompt")
                    assertEquals("Review: abc", ui.renderedText())
                    assertEquals("Review: abc", clipboard.getContents(DataFlavor.stringFlavor))
                }
                copyToClipboard("changed")
                ui.clickButton("Copy Prompt")
                assertEquals("Review: abc", ui.renderedText())
                assertEquals("Review: abc", clipboard.getContents(DataFlavor.stringFlavor))
                copyToClipboard("refreshed")
                ui.clickFileAction("Refresh Context")
                waitFor("explicit refresh updates the preview", 30.seconds) { ui.renderedText() == "Review: refreshed" }
                ui.clickButton("Copy Prompt")
                assertEquals("Review: refreshed", ui.renderedText())
                assertEquals("Review: refreshed", clipboard.getContents(DataFlavor.stringFlavor))

                directory.resolve("prompt.md").writeText("Updated: {{clipboard}}")
                ui.waitForAccessibleText("Template changed on disk. Reload Template to use the new version.")
                assertEquals("Review: refreshed", ui.renderedText())
                copyToClipboard("retained")
                ui.clickButton("Copy Prompt")
                ui.renderedText()
                assertEquals("retained", clipboard.getContents(DataFlavor.stringFlavor))
                ui.clickFileAction("Reload Template")
                waitFor("reload explicitly adopts the changed template", 30.seconds) { ui.renderedText() == "Updated: retained" }
                ui.clickButton("Copy Prompt")
                assertEquals("Updated: retained", ui.renderedText())
                assertEquals("Updated: retained", clipboard.getContents(DataFlavor.stringFlavor))

                val moved = directory.resolveSibling("moved-review")
                directory.moveTo(moved)
                ui.waitForAccessibleText(moved.toString())
                copyToClipboard("after move")
                ui.clickButton("Copy Prompt")
                assertEquals("Updated: retained", ui.renderedText())
                assertEquals("Updated: retained", clipboard.getContents(DataFlavor.stringFlavor))
            } finally {
                originalClipboard?.let(::copyToClipboard)
            }
        }
    }

    @Test
    fun `editor focus does not retarget insertion and changed ranges require reselection`() {
        val harness = StarterHarness.create("editor-snapshot")
        harness.workspace.project.resolve("source.txt").writeText("alpha\nbeta\n")
        harness.workspace.project.resolve("other.txt").writeText("untouched")
        val directory = harness.workspace.templates.createTemplate(
            "selection-review", "Selection review", "9fc5f499-9783-4bbb-a8ab-77dd1fe01db1",
        )
        directory.resolve("prompt.md").writeText("Review: {{ide.selection}}")
        harness.run { ui ->
            openFile("source.txt")
            val sourceEditor = ideFrame().codeEditorForFile("source.txt")
            sourceEditor.setSelection(0, 5)
            val sourceDocument = sourceEditor.document
            ui.open().selectTemplate("Selection review")
            waitFor("preview captures the selected text", 30.seconds) { ui.renderedText() == "Review: alpha" }
            openFile("other.txt")
            val otherDocument = ideFrame().codeEditorForFile("other.txt").document
            ui.clickButton("Insert into source.txt")
            assertEquals("Review: alpha", ui.renderedText())
            assertEquals("Review: alpha\nbeta\n", withReadAction { sourceDocument.getText() })
            assertEquals("untouched", withReadAction { otherDocument.getText() })
            ui.clickButton("Insert into source.txt")
            ui.renderedText() // Flush the posted button event before checking that the stale range was rejected.
            assertEquals("Review: alpha\nbeta\n", withReadAction { sourceDocument.getText() })

            openFile("source.txt")
            invokeAction("\$Undo", component = ideFrame().codeEditorForFile("source.txt").component)
            assertEquals("alpha\nbeta\n", withReadAction { sourceDocument.getText() })
            ideFrame().codeEditorForFile("source.txt").apply {
                text = "gamma\nbeta\n"
                setSelection(0, 5)
            }
            assertEquals("Review: alpha", ui.renderedText())
            ui.clickFileAction("Refresh Context")
            waitFor("refresh uses the changed source", 30.seconds) { ui.renderedText() == "Review: gamma" }
            ui.clickFileAction("Use Active Editor as Insertion Target")
            ui.clickButton("Insert into source.txt")
            ui.renderedText()
            assertEquals("Review: gamma\nbeta\n", withReadAction { sourceDocument.getText() })

            ui.clickFileAction("Refresh Context")
            ui.waitForAccessibleText("The source editor has no selection. Select text and Refresh Context.")
            copyToClipboard("unavailable context sentinel")
            ui.clickButton("Copy Prompt")
            ui.renderedText()
            assertEquals("unavailable context sentinel", utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor))
            ideFrame().codeEditorForFile("source.txt").setSelection(0, 13)
            ui.clickFileAction("Refresh Context")
            waitFor("selection recovers after explicit refresh", 30.seconds) { ui.renderedText() == "Review: Review: gamma" }
        }
    }
}

@Remote("com.intellij.openapi.ide.CopyPasteManager")
internal interface IdeClipboard {
    fun getInstance(): IdeClipboard
    fun getContents(flavor: DataFlavor): String?
}
