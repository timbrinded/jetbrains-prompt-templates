package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.MetadataDecodeResult
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import dev.timbrinded.prompttemplates.core.escapePlaceholderOpenings
import java.awt.datatransfer.DataFlavor
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class TemplateCreationIdeTest {
    @Test
    fun `duplicate preserves authored metadata and source while save validates the chosen folder`() {
        val harness = StarterHarness.create("duplicate-template")
        val codec = TemplateMetadataCodec()
        val source = harness.workspace.templates.createTemplate("Source/review", "Review", SOURCE_ID)
        val body = "{{goal}}\n{{depth}}\n{{clipboard}}\n\\{{literal}}\n"
        val metadata = TemplateMetadata(
            id = SOURCE_ID, name = "Review", description = "Authored description", tags = listOf("code, api", "review"),
            variables = listOf(
                PromptVariable("goal", "Goal", type = PromptVariableType.MULTILINE, description = "Authored help",
                    defaultValue = "Authored default", placeholder = "Enter a goal", minimumRows = 4),
                PromptVariable("depth", "Depth", type = PromptVariableType.ENUM,
                    options = listOf(EnumOption("quick", "Quick", "Quick"), EnumOption("deep", "Deep", "Deep")), defaultValue = "quick"),
                PromptVariable("unused", "Unused", required = false, defaultValue = "Unused default"),
            ),
        )
        source.resolve("prompt.md").writeText(body)
        source.resolve("prompt.meta.json").writeText(codec.encode(metadata))
        harness.workspace.templates.createTemplate("Destination/existing", "Review copy", OTHER_ID)
        harness.run { main ->
            main.dismissTrialNotification()
            copyToClipboard("Private captured context")
            main.open().selectTemplate("Source", "Review")
            ideFrame().textField { and(byClass("JBTextArea"), byAccessibleName("Goal")) }.waitFound().text = "Private invocation value"
            waitFor("transient input is rendered", 30.seconds) { main.renderedText().startsWith("Private invocation value") }
            val before = harness.workspace.templates.manifest()

            fun duplicate() {
                main.clickFileAction("Duplicate Template…")
                val popup = ui.popup().waitFound(30.seconds)
                val folders = popup.list()
                assertEquals(listOf("/ (Library root)", "Destination", "Source"), folders.items)
                assertEquals(listOf("Source"), folders.selectedItems)
                folders.keyboard { up(); enter() }
                popup.waitNotFound(30.seconds)
                assertEquals("Review copy (2)", authorName().text)
                assertEquals(body, authorMarkdown().text)
            }
            duplicate()
            main.clickButton("Cancel")
            ideFrame().button("Copy Prompt").waitFound(30.seconds)
            assertEquals(before, harness.workspace.templates.manifest())
            duplicate()
            authorName().text = "Review copy"
            main.clickButton("Save Template")
            main.waitForAccessibleText("An entry named 'Review copy' already exists in this folder.")
            assertEquals(body, authorMarkdown().text)
            authorName().text = "Review fork"
            main.clickButton("Save Template")
            main.waitForPath("Destination", "Review fork")
            val created = harness.workspace.library.resolve("Destination").listDirectoryEntries()
                .single { it.resolve("prompt.meta.json").isRegularFile() && it.fileName.toString() != "existing" }
            val saved = assertIs<MetadataDecodeResult.Success>(codec.decode(created.resolve("prompt.meta.json").readText())).metadata
            assertNotEquals(metadata.id, saved.id)
            assertEquals(metadata.copy(id = saved.id, name = "Review fork"), saved)
            assertEquals(body, created.resolve("prompt.md").readText())
            assertEquals(codec.encode(metadata), source.resolve("prompt.meta.json").readText())
            assertEquals(body, source.resolve("prompt.md").readText())
        }
    }

    @Test
    fun `selection capture is immutable and literal by default with explicit placeholder interpretation`() {
        val harness = StarterHarness.create("capture-selection")
        val literal = "  {{user}}\n\\{{escaped}} \\\\{{twice}}\n{{unfinished\n"
        val sourceText = "before\n${literal}after\n"
        val source = harness.workspace.project.resolve("source.txt").apply { writeText(sourceText) }
        harness.run { main ->
            main.dismissTrialNotification()
            main.open()
            openFile(source.fileName.toString())
            val editor = ideFrame().codeEditorForFile("source.txt")
            editor.setSelection(0, 0)
            invokeAction(CAPTURE_ACTION, component = editor.component)
            main.waitForNotification("Select text in an editor")
            val before = harness.workspace.templates.manifest()
            ideFrame().keyboard { escape() } // Dismiss the no-selection notification before modal keyboard checks.

            fun captureLiteral() {
                editor.setSelection(7, 7 + literal.length)
                invokeAction(CAPTURE_ACTION, component = editor.component)
                val dialog = ui.dialog(title = "Create Template from Selection").waitFound(30.seconds)
                editor.setSelection(0, 6) // Selection changes after capture cannot change the draft.
                dialog.keyboard { enter() } // Preserve Literally is the safe default.
                dialog.waitNotFound(30.seconds)
                assertEquals(escapePlaceholderOpenings(literal), authorMarkdown().text)
            }
            captureLiteral()
            main.clickButton("Cancel")
            waitFor("clean selection draft closes", 30.seconds) { ideFrame().button("Save Template").notPresent() }
            assertEquals(before, harness.workspace.templates.manifest())
            captureLiteral()
            authorName().text = "Captured literal"
            main.clickButton("Save Template")
            main.waitForPath("Captured literal")
            main.clickButton("Copy Prompt")
            waitFor("Copy delivers the exact captured selection", 10.seconds) {
                utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor) == literal
            }
            val created = harness.workspace.library.listDirectoryEntries().single { it.resolve("prompt.meta.json").isRegularFile() }
            val saved = assertIs<MetadataDecodeResult.Success>(TemplateMetadataCodec().decode(created.resolve("prompt.meta.json").readText())).metadata
            assertEquals(emptyList(), saved.variables)
            assertEquals(sourceText, withReadAction { editor.document.getText() })

            editor.setSelection(9, 17) // {{user}} from the original document.
            invokeAction(CAPTURE_ACTION, component = editor.component)
            val choice = ui.dialog(title = "Create Template from Selection").waitFound(30.seconds)
            choice.button("Interpret Placeholders").setFocus()
            choice.button("Interpret Placeholders").keyboard { space() }
            choice.waitNotFound(30.seconds)
            assertEquals("{{user}}", authorMarkdown().text)
            authorName().text = "Dirty interpreted draft"
            invokeAction(CAPTURE_ACTION, component = editor.component)
            main.waitForNotification("Save or cancel the open template")
            assertEquals("Dirty interpreted draft", authorName().text)
            main.clickButton("Save Template")
            main.waitForPath("Dirty interpreted draft")
            val interpreted = harness.workspace.library.listDirectoryEntries()
                .single { it.resolve("prompt.meta.json").isRegularFile() && it != created }
            val interpretedMetadata = assertIs<MetadataDecodeResult.Success>(TemplateMetadataCodec().decode(interpreted.resolve("prompt.meta.json").readText())).metadata
            assertEquals(listOf("user"), interpretedMetadata.variables.map(PromptVariable::key))
            assertEquals(sourceText, withReadAction { editor.document.getText() })
        }
    }

    private fun Driver.authorName() = ideFrame().textField { and(byClass("JBTextField"), byAccessibleName("Name:")) }.waitFound(30.seconds)
    private fun Driver.authorMarkdown() = ideFrame().editor("//div[@class='EditorComponentImpl' and @accessiblename='Template Markdown']").waitFound(30.seconds)

    companion object {
        private const val CAPTURE_ACTION = "PromptTemplates.CreateFromSelection"
        private const val SOURCE_ID = "95a3cce2-a90c-4bdc-9887-623470364aa0"
        private const val OTHER_ID = "314736d8-cdef-4ac4-a1bd-d357d3300b82"
    }
}
