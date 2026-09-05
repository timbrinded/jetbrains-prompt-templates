package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class ReferencedInputsIdeTest {
    @Test
    fun `validation reveals the multiline caret and unused fields never enter the use form`() {
        val harness = StarterHarness.create("referenced-inputs")
        val id = "a304d2d1-b99e-4a0f-ae9d-639a6023eafe"
        val directory = harness.workspace.templates.createTemplate("review", "Review", id)
        val fillers = (1..6).map { PromptVariable("context$it", "Context $it", defaultValue = "Detail $it") }
        val markdown = "{{notes}}\n{{notes}}\n{{title}} {{mode}}\n" +
            fillers.joinToString("\n") { "{{${it.key}}}" } + "\n\\{{escaped}} {{clipboard}}"
        directory.resolve("prompt.md").writeText(markdown)
        directory.resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
            id = id, name = "Review", variables = listOf(
                PromptVariable("unused", "Unused required field"),
                PromptVariable("mode", "Mode", type = PromptVariableType.ENUM,
                    options = listOf(EnumOption("short", "Short", "Short"), EnumOption("long", "Long", "Long")), defaultValue = "short"),
                PromptVariable("title", "Title", defaultValue = "Review"),
            ) + fillers + listOf(
                PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE),
                PromptVariable("escaped", "Escaped required field"),
            ),
        )))
        val before = TestLibrary(directory).manifest()
        harness.run { ui ->
            copyToClipboard("context snapshot")
            ui.open().selectTemplate("Review")
            val frame = ideFrame()
            val notes = frame.x { and(byClass("JBTextArea"), byAccessibleName("Notes")) }.waitFound(30.seconds)
            val title = frame.textField { and(byClass("JBTextField"), byAccessibleName("Title")) }.waitFound()
            val mode = frame.comboBox { and(byClass("ComboBox"), byAccessibleName("Mode")) }.waitFound()
            waitFor("context capture has completed", 30.seconds) { ui.renderedText().endsWith("{{escaped}} context snapshot") }
            ui.clickButton("Copy Prompt")
            waitFor("validation focuses the multiline input", 10.seconds) { notes.isFocusOwner() }
            waitFor("the multiline input is visible after validation", 10.seconds) {
                withContext(OnDispatcher.EDT) { cast(notes.component, TestInputArea::class).getVisibleRect() }.height > 0
            }
            notes.keyboard { typeText("First line"); enter(); typeText("Second line") }
            waitFor("typing immediately updates the multiline value", 30.seconds) {
                ui.renderedText().startsWith("First line\nSecond line\nFirst line\nSecond line")
            }
            assertTrue(frame.textField { and(byClass("JBTextField"), byAccessibleName("Unused required field")) }.notPresent())
            assertTrue(frame.textField { and(byClass("JBTextField"), byAccessibleName("Escaped required field")) }.notPresent())
            assertTrue(mode.boundsOnScreen.y < title.boundsOnScreen.y)
            assertTrue(title.boundsOnScreen.y < notes.boundsOnScreen.y)

            title.text = ""
            ui.clickButton("Copy Prompt")
            waitFor("text validation retains caret focus", 10.seconds) { title.isFocusOwner() }
            title.keyboard { typeText("Typed title") }
            mode.setFocus()
            waitFor("enum retains keyboard focus", 10.seconds) { mode.isFocusOwner() }
            mode.selectItem("Long")
            val expected = "First line\nSecond line\nFirst line\nSecond line\nTyped title Long\n" +
                fillers.joinToString("\n") { it.defaultValue.orEmpty() } + "\n{{escaped}} context snapshot"
            waitFor("all entered values render", 30.seconds) { ui.renderedText() == expected }
            ui.clickButton("Copy Prompt")
            waitFor("valid output is copied despite unused required definitions", 30.seconds) {
                utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor) == expected
            }
            ui.clickButton("Edit")
            val variables = frame.list { and(byClass("JBList"), byAccessibleName("Template variables")) }.waitFound()
            assertTrue(variables.items.any { it.contains("unused") })
            assertTrue(variables.items.any { it.contains("escaped") })
            ui.clickButton("Cancel")
            assertEquals(before, TestLibrary(directory).manifest())
        }
    }
}

@Remote("javax.swing.JTextArea")
private interface TestInputArea {
    fun getVisibleRect(): Rectangle
}
