package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.textField
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class AuthorCancelIdeTest {
    @Test
    fun `cancel protects all author inputs and visual transitions keep the same draft`() {
        val harness = StarterHarness.create("author-cancel")
        val id = "bf25002a-45b7-4664-8ed0-2274e3ef652c"
        val directory = harness.workspace.templates.createTemplate("review", "Review", id)
        directory.resolve("prompt.md").writeText("{{mode}}")
        directory.resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
            id = id, name = "Review", variables = listOf(PromptVariable(
                key = "mode", label = "Mode", type = PromptVariableType.ENUM,
                options = listOf(EnumOption("quick", "Quick", "Quick"), EnumOption("deep", "Deep", "Deep")),
                defaultValue = "quick",
            )),
        )))
        harness.run { ui ->
            ui.open().selectTemplate("Review")
            val before = harness.workspace.templates.manifest()
            ui.clickButton("Edit")
            ui.toggleWordWrap()
            ui.clickButton("Cancel")
            ideFrame().button("Copy Prompt").waitFound(30.seconds) // No dialog for a clean draft or a visual change.
            ui.newTemplate()
            ui.clickButton("Cancel")
            ideFrame().button("Copy Prompt").waitFound(30.seconds)

            ui.clickButton("Edit")
            val markdown = ideFrame().editor("//div[@class='EditorComponentImpl' and @accessiblename='Template Markdown']").waitFound(30.seconds)
            markdown.text = "Changed {{mode}}"
            ui.clickButton("Cancel")
            ui.chooseUnsavedDraftAction("Keep Editing")
            assertEquals("Changed {{mode}}", markdown.text)
            markdown.text = "{{mode}}"
            ui.clickButton("Cancel")
            ideFrame().button("Copy Prompt").waitFound(30.seconds) // A full revert is clean again.

            ui.clickButton("Edit")
            fun name() = ideFrame().textField { and(byClass("JBTextField"), byAccessibleName("Name:")) }.waitFound(30.seconds)
            fun choices() = ideFrame().textField { and(byClass("JBTextField"), byAccessibleName("Enum choices (; separated):")) }.waitFound(30.seconds)
            fun type() = ideFrame().comboBox { and(byClass("ComboBox"), byAccessibleName("Type:")) }.waitFound(30.seconds)
            name().text = "Kept draft"
            choices().text = "Short; Long"
            ui.clickButton("Cancel")
            ui.chooseUnsavedDraftAction("Keep Editing")
            assertEquals("Kept draft", name().text)
            assertEquals("Short; Long", choices().text)
            assertEquals("ENUM", type().getSelectedItem())
            type().selectItem("MULTILINE")
            ui.clickButton("Cancel")
            ui.chooseUnsavedDraftAction("Keep Editing")
            assertEquals("MULTILINE", type().getSelectedItem())
            ui.hide()
            ui.open()
            assertEquals("Kept draft", name().text)
            ui.setIdeWidth(600)
            assertEquals("Kept draft", name().text)
            ui.setIdeWidth(1400)
            assertEquals("Kept draft", name().text)
            assertEquals("MULTILINE", type().getSelectedItem())
            ui.clickButton("Cancel")
            ui.chooseUnsavedDraftAction("Discard")
            ideFrame().button("Copy Prompt").waitFound(30.seconds)
            assertEquals(before, harness.workspace.templates.manifest())

            ui.clickButton("Edit")
            name().text = "Saved"
            ui.clickButton("Save Template")
            ui.waitForPath("Saved")
            ui.clickButton("Edit")
            ui.clickButton("Cancel")
            ideFrame().button("Copy Prompt").waitFound(30.seconds)
            ui.clickButton("Edit")
            name().text = ""
            ui.clickButton("Save Template")
            ui.waitForAccessibleText("Template name is required.")
            ui.clickButton("Cancel")
            ui.chooseUnsavedDraftAction("Keep Editing")
            assertEquals("", name().text)
            ui.clickButton("Cancel")
            ui.chooseUnsavedDraftAction("Discard")
            ideFrame().button("Copy Prompt").waitFound(30.seconds)
        }
    }
}
