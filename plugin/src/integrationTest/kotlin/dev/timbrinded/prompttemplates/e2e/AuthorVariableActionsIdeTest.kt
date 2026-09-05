package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.MetadataDecodeResult
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.event.KeyEvent
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class AuthorVariableActionsIdeTest {
    @Test
    fun `insert chooser reuses inputs and inserts explained context without user schema`() {
        val harness = StarterHarness.create("author-insert")
        val id = "99f1ec6a-0a33-4bab-b509-32618ac92f59"
        val directory = harness.workspace.templates.createTemplate("review", "Review", id)
        val markdown = "{{goal}} \\{{goal}} {{goal}}\n"
        val goal = PromptVariable("goal", "Goal", defaultValue = "Keep this default")
        val codec = TemplateMetadataCodec()
        directory.resolve("prompt.md").writeText(markdown)
        directory.resolve("prompt.meta.json").writeText(codec.encode(TemplateMetadata(id = id, name = "Review", variables = listOf(goal))))
        harness.run { main ->
            main.dismissTrialNotification()
            main.open().selectTemplate("Review")
            main.clickButton("Edit")
            val frame = ideFrame()
            val author = frame.editor("//div[@class='EditorComponentImpl' and @accessiblename='Template Markdown']").waitFound()
            val key = frame.textField { and(byClass("JBTextField"), byAccessibleName("Variable key")) }
            fun insert(choice: String) {
                main.clickAuthorAction("Insert Variable…")
                val popup = ui.popup().waitFound(30.seconds)
                val choices = popup.list()
                val index = choices.items.indexOfFirst { choice in it }
                assertTrue(index >= 0, choices.items.toString())
                choices.setFocus()
                choices.keyboard { this.key(KeyEvent.VK_HOME); repeat(index) { down() }; enter() }
                popup.waitNotFound(30.seconds)
            }
            author.moveCaretToOffset(markdown.length)
            insert("Input — {{goal}}")
            waitFor("existing input is selected and focused", 10.seconds) { key.text == "goal" && key.isFocusOwner() }
            assertEquals(markdown + "{{goal}}", author.text)
            author.setFocus()
            invokeAction("\$Undo", component = author.component)
            waitFor("insertion undo restores text", 10.seconds) { author.text == markdown }
            invokeAction("\$Redo", component = author.component)
            waitFor("insertion redo restores token", 10.seconds) { author.text == markdown + "{{goal}}" }
            author.moveCaretToOffset(author.text.length)
            insert("{{ide.project.name}}")
            main.waitForAccessibleText("ide.project.name: The current project name.")
            val expected = markdown + "{{goal}}{{ide.project.name}}"
            assertEquals(expected, author.text)
            author.moveCaretToOffset(10) // Immediately after the literal escape backslash.
            insert("Input — {{goal}}")
            assertEquals(expected, author.text)
            main.waitForAccessibleText("A placeholder cannot start here.")
            main.clickButton("Save Template")
            frame.button("Copy Prompt").waitFound(30.seconds)
            assertEquals(listOf(goal), assertIs<MetadataDecodeResult.Success>(codec.decode(directory.resolve("prompt.meta.json").readText())).metadata.variables)
            assertEquals(expected, directory.resolve("prompt.md").readText())
        }
    }

    @Test
    fun `extraction validates cancels and coordinates schema with native undo redo and rename`() {
        val harness = StarterHarness.create("author-extract")
        val id = "884e6a49-a1b7-4af0-9d82-b2448b370021"
        val directory = harness.workspace.templates.createTemplate("review", "Review", id)
        val selected = "  Selected line one\nline two  "
        val markdown = "Heading\n${selected}\nTail\n{{other}} \\{{literal}}"
        val codec = TemplateMetadataCodec()
        directory.resolve("prompt.md").writeText(markdown)
        directory.resolve("prompt.meta.json").writeText(codec.encode(TemplateMetadata(id = id, name = "Review", variables = listOf(PromptVariable("other", "Other", defaultValue = "Other default")))))
        harness.run { main ->
            main.dismissTrialNotification()
            main.open().selectTemplate("Review")
            main.clickButton("Edit")
            val frame = ideFrame()
            val author = frame.editor("//div[@class='EditorComponentImpl' and @accessiblename='Template Markdown']").waitFound()
            fun field(name: String) = frame.textField { and(byClass("JBTextField"), byAccessibleName(name)) }.waitFound()
            val initial = harness.workspace.templates.manifest()
            fun openExtraction() {
                author.setSelection(8, 8 + selected.length)
                main.clickAuthorAction("Extract as Variable…")
            }
            openExtraction()
            ui.dialog(title = "Extract as Variable") {
                waitFound(30.seconds)
                assertEquals("MULTILINE", comboBox().getSelectedItem())
                val input = textField { and(byClass("JBTextField"), byAccessibleName("Variable key:")) }
                input.text = "clipboard"
                input.keyboard { enter() }
                waitFor("reserved key blocks extraction", 10.seconds) { !button("Extract").isEnabled() }
                input.text = "other"
                input.keyboard { enter() }
                waitFor("duplicate key blocks extraction", 10.seconds) { !button("Extract").isEnabled() }
                val cancel = button("Cancel")
                cancel.setFocus()
                cancel.keyboard { space() }
            }
            ui.waitForNoOpenedDialogs()
            assertEquals(markdown, author.text)
            assertEquals(initial, harness.workspace.templates.manifest())
            openExtraction()
            ui.dialog(title = "Extract as Variable") {
                waitFound(30.seconds)
                textField { and(byClass("JBTextField"), byAccessibleName("Variable key:")) }.text = "task_notes"
                val retain = x { and(byClass("JBCheckBox"), byAccessibleName("Use selected text as authored default")) }.waitFound()
                retain.setFocus()
                retain.keyboard { space(); enter() }
            }
            ui.waitForNoOpenedDialogs()
            val extracted = markdown.replace(selected, "{{task_notes}}")
            waitFor("extraction focuses its new inspector", 10.seconds) { author.text == extracted && field("Variable key").text == "task_notes" && field("Variable key").isFocusOwner() }
            val variables = frame.list { and(byClass("JBList"), byAccessibleName("Template variables")) }
            variables.setFocus()
            variables.keyboard { key(KeyEvent.VK_HOME) }
            waitFor("other variable selected", 10.seconds) { field("Variable key").text == "other" }
            field("Label:").text = "Later other label"
            author.setFocus()
            invokeAction("\$Undo", component = author.component)
            waitFor("undo restores literal and removes definition", 10.seconds) { author.text == markdown && variables.items.size == 1 }
            assertEquals("Later other label", field("Label:").text)
            invokeAction("\$Redo", component = author.component)
            waitFor("redo restores extraction and exact default", 10.seconds) { author.text == extracted && variables.items.size == 2 }
            variables.setFocus()
            variables.keyboard { key(KeyEvent.VK_END) }
            waitFor("extracted variable selected", 10.seconds) { field("Variable key").text == "task_notes" }
            field("Variable key").text = "notes"
            main.clickButton("Rename")
            val renamed = extracted.replace("{{task_notes}}", "{{notes}}")
            waitFor("rename updates Markdown", 10.seconds) { author.text == renamed }
            author.setFocus()
            invokeAction("\$Undo", component = author.component)
            waitFor("undo rename restores original key", 10.seconds) { author.text == extracted && field("Variable key").text == "task_notes" }
            invokeAction("\$Undo", component = author.component)
            waitFor("second undo removes extraction", 10.seconds) { author.text == markdown && variables.items.size == 1 }
            invokeAction("\$Redo", component = author.component)
            waitFor("first redo restores extraction", 10.seconds) { author.text == extracted && variables.items.size == 2 }
            invokeAction("\$Redo", component = author.component)
            waitFor("second redo restores rename", 10.seconds) { author.text == renamed && field("Variable key").text == "notes" }
            // A second extraction uses the unchecked default and the suggested single-line Text type.
            author.setSelection(0, 7)
            main.clickAuthorAction("Extract as Variable…")
            ui.dialog(title = "Extract as Variable") {
                waitFound(30.seconds)
                assertEquals("TEXT", comboBox().getSelectedItem())
                val input = textField { and(byClass("JBTextField"), byAccessibleName("Variable key:")) }
                input.text = "heading"
                input.keyboard { enter() }
            }
            ui.waitForNoOpenedDialogs()
            assertEquals(initial, harness.workspace.templates.manifest())
            main.clickButton("Save Template")
            frame.button("Copy Prompt").waitFound(30.seconds)
            val saved = assertIs<MetadataDecodeResult.Success>(codec.decode(directory.resolve("prompt.meta.json").readText())).metadata.variables
            assertEquals(listOf("other", "notes", "heading"), saved.map(PromptVariable::key))
            assertEquals("Later other label", saved[0].label)
            assertEquals(selected, saved[1].defaultValue)
            assertEquals(PromptVariableType.MULTILINE, saved[1].type)
            assertNull(saved[2].defaultValue)
            assertEquals(PromptVariableType.TEXT, saved[2].type)
            assertEquals(renamed.replace("Heading", "{{heading}}"), directory.resolve("prompt.md").readText())
        }
    }
}
