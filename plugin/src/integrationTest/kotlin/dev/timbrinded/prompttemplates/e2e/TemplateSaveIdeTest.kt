package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.MetadataDecodeResult
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import kotlin.io.path.exists
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class TemplateSaveIdeTest {
    @Test
    fun `overwrite uses the reviewed revision and preserves the draft after a second external change`() {
        val harness = StarterHarness.create("save-conflict")
        val directory = harness.workspace.templates.createTemplate("original", "Original", "b932f7e2-3728-48c6-856b-a2375d8149f6")
        val markdown = directory.resolve("prompt.md")
        markdown.writeText("Original body")
        harness.run { ui ->
            ui.open().selectTemplate("Original")
            ui.clickButton("Edit")
            val name = ideFrame().textField { and(byClass("JBTextField"), byAccessibleName("Name:")) }.waitFound(30.seconds)
            name.text = "Draft name"
            markdown.writeText("External one")
            ui.clickButton("Save Template")
            ui.confirmTemplateOverwrite { shown ->
                assertTrue(shown.startsWith("External one\n"))
                markdown.writeText("External two")
            }
            ui.waitForNotification("Your draft is unchanged")
            assertEquals("External two", markdown.readText())
            assertEquals("Draft name", name.text)
            assertFalse(directory.resolve(".prompt-template-save.json").exists())

            ui.clickButton("Save Template")
            ui.confirmTemplateOverwrite { shown -> assertTrue(shown.startsWith("External two\n")) }
            ui.waitForPath("Draft name")
            waitFor("approved draft is saved as a complete pair", 30.seconds) {
                markdown.readText() == "Original body" &&
                    assertIs<MetadataDecodeResult.Success>(TemplateMetadataCodec().decode(directory.resolve("prompt.meta.json").readText()))
                        .metadata.name == "Draft name"
            }
            assertFalse(directory.resolve(".prompt-template-save.json").exists())
            val journal = directory.resolve(".prompt-template-save.json")
            journal.writeText("invalid journal for recovery check")
            ui.waitForAccessibleText("Save recovery needs attention: invalid journal")
            assertEquals("invalid journal for recovery check", journal.readText())
            assertEquals("Original body", markdown.readText())
            journal.deleteExisting()
            ui.waitForPath("Draft name")
        }
    }
}
