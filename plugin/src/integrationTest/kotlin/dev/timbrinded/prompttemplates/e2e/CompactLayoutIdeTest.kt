package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.Rectangle
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class CompactLayoutIdeTest {
    @Test
    fun `author controls and use actions remain reachable after resizing`() = exerciseLayout(1f)

    @Test
    fun `author controls and use actions remain reachable at 150 percent scale`() = exerciseLayout(1.5f)

    private fun exerciseLayout(scale: Float) {
        val harness = StarterHarness.create("compact-layout-$scale", uiScale = scale)
        val id = "b51c729c-a060-4ba5-8b16-9e8b335a9192"
        val directory = harness.workspace.templates.createTemplate("review", "Review", id)
        directory.resolve("prompt.md").writeText("{{goal}}\n{{mode}}")
        directory.resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
            id = id, name = "Review", variables = listOf(
                PromptVariable(key = "goal", label = "Goal", defaultValue = "Review"),
                PromptVariable(
                    key = "mode", label = "Mode with a long descriptive label", type = PromptVariableType.ENUM,
                    description = "Choose the amount of detail to include in the review and its suggested changes.",
                    options = listOf(EnumOption("quick", "Quick", "Quick"), EnumOption("deep", "Deep", "Deep")),
                    defaultValue = "quick",
                ),
            ),
        )))
        harness.run { ui ->
            ui.open().selectTemplate("Review")
            assertEquals(scale, utility(TestUiScale::class).scale(1f))
            ui.clickButton("Edit")
            val frame = ideFrame()
            fun field(name: String) = frame.textField { and(byClass("JBTextField"), byAccessibleName(name)) }.waitFound(30.seconds)
            val variables = frame.list { and(byClass("JBList"), byAccessibleName("Template variables")) }.waitFound()
            val inspector = frame.x { and(byClass("JBScrollPane"), byAccessibleName("Variable inspector")) }.waitFound()
            fun assertVisible(control: UiComponent) {
                waitFor("$control is fully visible", 5.seconds) {
                    val bounds = control.boundsOnScreen
                    val visible = withContext(OnDispatcher.EDT) { cast(control.component, TestVisibleComponent::class).getVisibleRect() }
                    bounds.width > 0 && bounds.height > 0 && visible == Rectangle(0, 0, bounds.width, bounds.height)
                }
            }
            waitFor("wide author uses columns", 30.seconds) {
                variables.boundsOnScreen.maxX <= inspector.boundsOnScreen.x
            }
            variables.setFocus()
            variables.keyboard { down() }
            waitFor("keyboard selects mode", 30.seconds) { field("Variable key").text == "mode" }
            field("Name:").text = "Compact draft"
            field("Tags:").text = "compact, keyboard"
            field("Enum choices (; separated):").text = "Short; Detailed"
            val markdown = frame.editor("//div[@class='EditorComponentImpl' and @accessiblename='Template Markdown']").waitFound()
            markdown.text = "Describe the changes.\n{{goal}}\n{{mode}}"
            field("Variable key").text = "pending_rename"

            ui.setIdeWidth(600)
            waitFor("narrow author stacks navigation above the inspector", 30.seconds) {
                variables.boundsOnScreen.maxY <= inspector.boundsOnScreen.y
            }
            assertEquals("Compact draft", field("Name:").text)
            assertEquals("pending_rename", field("Variable key").text)
            assertEquals("Short; Detailed", field("Enum choices (; separated):").text)
            assertTrue(variables.isSelectedIndex(1))
            field("Variable key").setFocus()
            waitFor("key gains focus", 30.seconds) { field("Variable key").isFocusOwner() }
            // Traverse the real focus cycle, including controls outside the current scroll viewport.
            listOf(
                frame.button("Rename"),
                field("Label:"),
                frame.x { and(byClass("ComboBox"), byAccessibleName("Type:")) },
                inspector.textField { and(byClass("JBTextField"), byAccessibleName("Description:")) },
                field("Enum choices (; separated):"),
                frame.x { and(byClass("ComboBox"), byAccessibleName("Default option:")) },
            ).forEach { control ->
                frame.keyboard { tab() }
                waitFor("Tab reaches $control", 30.seconds) { control.isFocusOwner() }
                assertVisible(control)
            }
            assertTrue(frame.x { and(byClass("ComboBox"), byAccessibleName("Default option:")) }.isFocusOwner())
            assertTrue(field("Enum choices (; separated):").boundsOnScreen.width >= 120 * scale)
            assertVisible(frame.button("Cancel"))
            assertVisible(frame.button("Save Template"))

            ui.setIdeWidth(1400)
            assertEquals("compact, keyboard", field("Tags:").text)
            assertEquals("pending_rename", field("Variable key").text)
            assertEquals("Describe the changes.\n{{goal}}\n{{mode}}", markdown.text)
            ui.setIdeWidth(600)
            ui.clickButton("Save Template")
            frame.button("Copy Prompt").waitFound(30.seconds)
            listOf("Copy Prompt", "Insert into README.md", "Edit").forEach { assertVisible(frame.button(it)) }
            assertTrue(frame.button("Delete").notPresent())
            ui.clickFileAction("Delete")
            val confirmation = this.ui.dialog(title = "Delete Prompt Template").waitFound(30.seconds)
            val no = confirmation.button("No")
            no.setFocus()
            waitFor("No has keyboard focus", 30.seconds) { no.isFocusOwner() }
            no.keyboard { space() }
            confirmation.waitNotFound(30.seconds)
            assertTrue(directory.resolve("prompt.md").toFile().exists())
            ui.clickButton("Edit")
            variables.invokeSelectNextRowAction()
            assertEquals("Short; Detailed", field("Enum choices (; separated):").text)
            ui.clickButton("Cancel")
            frame.button("Copy Prompt").waitFound(30.seconds)
            ui.setIdeWidth(1400)
        }
    }
}

@Remote("javax.swing.JComponent")
private interface TestVisibleComponent {
    fun getVisibleRect(): Rectangle
}

@Remote("com.intellij.ui.scale.JBUIScale")
private interface TestUiScale {
    fun scale(value: Float): Float
}
