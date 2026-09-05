package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.MetadataDecodeResult
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class VariableDefaultsIdeTest {
    @Test
    fun `author defaults and order survive narrow editing and reset retains captured context`() {
        val harness = StarterHarness.create("variable-defaults")
        val id = "da13d9bc-5d41-4271-a9b5-9f85fd7fd627"
        val directory = harness.workspace.templates.createTemplate("review", "Review", id)
        val markdown = "{{goal}}\n{{notes}}\n{{mode}}\n{{clipboard}}"
        val codec = TemplateMetadataCodec()
        directory.resolve("prompt.md").writeText(markdown)
        directory.resolve("prompt.meta.json").writeText(codec.encode(TemplateMetadata(id = id, name = "Review", variables = listOf(
            PromptVariable("goal", "Goal"),
            PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE, required = false),
            PromptVariable("mode", "Mode", type = PromptVariableType.ENUM, defaultValue = "quick-id",
                options = listOf(EnumOption("quick-id", "Quick", "Quick"), EnumOption("deep-id", "Deep", "Deep"))),
            PromptVariable("unused", "Unused", required = false),
        ))))
        harness.run { main ->
            main.dismissTrialNotification()
            main.open().selectTemplate("Review")
            main.clickButton("Edit")
            val frame = ideFrame()
            fun field(name: String, klass: String = "JBTextField") = frame.textField { and(byClass(klass), byAccessibleName(name)) }.waitFound(30.seconds)
            val variables = frame.list { and(byClass("JBList"), byAccessibleName("Template variables")) }.waitFound()
            fun select(index: Int, key: String) {
                variables.setFocus()
                variables.keyboard { this.key(KeyEvent.VK_HOME); repeat(index) { down() } }
                waitFor("selected variable $key", 30.seconds) { field("Variable key").text == key }
            }
            fun enableDefault() {
                val checkbox = frame.x { and(byClass("JBCheckBox"), byAccessibleName("Use authored default")) }.waitFound()
                checkbox.setFocus()
                checkbox.keyboard { space() }
            }
            enableDefault() // An authored empty default, distinct from Unused's absent default.
            assertEquals("", field("Default value:").text)
            field("Input placeholder:").text = "Enter a goal"
            select(1, "notes")
            main.setIdeWidth(600)
            enableDefault()
            val default = field("Default value:", "JBTextArea")
            default.setFocus()
            default.keyboard { typeText("Authored first"); enter(); typeText("Authored second") }
            val placeholder = field("Input placeholder:")
            default.keyboard { tab() }
            waitFor("Tab leaves multiline default and scrolls to placeholder", 10.seconds) { placeholder.isFocusOwner() }
            placeholder.text = "Enter detailed notes"
            val rowCount = frame.textField { byClass("JFormattedTextField") }.waitFound()
            rowCount.setFocus()
            rowCount.text = "8"
            val inspector = frame.x { and(byClass("JBScrollPane"), byAccessibleName("Variable inspector")) }
            inspector.textField { and(byClass("JBTextField"), byAccessibleName("Description:")) }.text = "Include details on separate lines."
            main.clickButton("Cancel")
            main.chooseUnsavedDraftAction("Keep Editing")
            assertEquals("Authored first\nAuthored second", default.text)
            assertEquals("8", rowCount.text)

            select(2, "mode")
            val defaultOption = frame.comboBox { and(byClass("ComboBox"), byAccessibleName("Default option:")) }.waitFound()
            defaultOption.selectItem("Deep")
            field("Enum choices (; separated):").text = "Quick"
            waitFor("removing the selected default chooses a surviving option", 10.seconds) { defaultOption.getSelectedItem() == "Quick" }
            field("Enum choices (; separated):").text = "Quick; Deep"
            defaultOption.selectItem("Deep")
            assertTrue(frame.x { and(byClass("JBCheckBox"), byAccessibleName("Use authored default")) }.notPresent())
            select(1, "notes")
            val up = frame.button("Move Up").waitFound()
            up.setFocus()
            up.keyboard { space() }
            waitFor("reordering retains selection by key", 10.seconds) { variables.isSelectedIndex(0) && field("Variable key").text == "notes" }
            val author = frame.editor("//div[@class='EditorComponentImpl' and @accessiblename='Template Markdown']").waitFound()
            assertEquals(markdown, author.text)
            copyToClipboard("Original context snapshot")
            main.clickButton("Save Template")
            frame.button("Copy Prompt").waitFound(30.seconds)
            main.clickFileAction("Reset Values to Defaults") // Editing defaults does not replace retained session inputs.
            val saved = assertIs<MetadataDecodeResult.Success>(codec.decode(directory.resolve("prompt.meta.json").readText())).metadata
            assertEquals(listOf("notes", "goal", "mode", "unused"), saved.variables.map(PromptVariable::key))
            assertEquals("", saved.variables[1].defaultValue)
            assertNull(saved.variables[3].defaultValue)
            assertEquals("Authored first\nAuthored second", saved.variables[0].defaultValue)
            assertEquals(8, saved.variables[0].minimumRows)
            assertEquals("Enter detailed notes", saved.variables[0].placeholder)
            assertEquals("deep-id", saved.variables[2].defaultValue)
            assertEquals(markdown, directory.resolve("prompt.md").readText())
            val persistedBeforeUse = harness.workspace.templates.manifest()

            val notes = field("Notes", "JBTextArea")
            val goal = field("Goal")
            assertTrue(notes.boundsOnScreen.y < goal.boundsOnScreen.y)
            assertEquals(8, withContext(OnDispatcher.EDT) { cast(notes.component, TestSizedTextArea::class).getRows() })
            main.clickButton("Copy Prompt")
            waitFor("empty authored required default still needs input", 10.seconds) { goal.isFocusOwner() }
            goal.text = "Private session goal"
            notes.text = "Private session notes"
            frame.comboBox { and(byClass("ComboBox"), byAccessibleName("Mode")) }.selectItem("Quick")
            copyToClipboard("Changed after capture")
            val resetNotes = frame.button { byAccessibleName("Reset Notes to Default") }.waitFound()
            resetNotes.setFocus()
            resetNotes.keyboard { space() }
            val expected = "Private session goal\nAuthored first\nAuthored second\nQuick\nOriginal context snapshot"
            waitFor("field reset restores one default and retains other inputs and context", 30.seconds) { main.renderedText() == expected }
            main.clickFileAction("Reset Values to Defaults")
            waitFor("all reset restores defaults without capturing clipboard again", 30.seconds) {
                field("Goal").text == "" && main.renderedText().endsWith("\nDeep\nOriginal context snapshot")
            }
            assertEquals(persistedBeforeUse, harness.workspace.templates.manifest())
            main.clickButton("Edit")
            assertEquals("notes", field("Variable key").text)
            assertEquals("Authored first\nAuthored second", field("Default value:", "JBTextArea").text)
            main.clickButton("Cancel")
            frame.button("Copy Prompt").waitFound(30.seconds)
            main.setIdeWidth(1400)
            field("Goal").text = "Final goal"
            main.clickButton("Copy Prompt")
            assertEquals("Final goal\nAuthored first\nAuthored second\nDeep\nChanged after capture",
                utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor))
        }
        val config = harness.workspace.evidence.resolve("isolated-paths.txt").readText().lineSequence()
            .first { it.startsWith("starter.config=") }.substringAfter('=')
        val settings = java.nio.file.Path.of(config).resolve("options/promptTemplates.xml").readText()
        for (secret in listOf("Private session goal", "Private session notes", "Original context snapshot", "Changed after capture")) {
            assertFalse(settings.contains(secret))
        }
    }
}

@Remote("javax.swing.JTextArea")
private interface TestSizedTextArea {
    fun getRows(): Int
}
