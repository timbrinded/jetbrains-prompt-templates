package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.tabbedPane
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.ContextValue
import dev.timbrinded.prompttemplates.core.MetadataDecodeResult
import dev.timbrinded.prompttemplates.core.StrictPromptRenderer
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import dev.timbrinded.prompttemplates.core.WorkedExample
import dev.timbrinded.prompttemplates.core.WorkedExamples
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class WorkedExamplesIdeTest {
    @Test
    fun `examples remain optional and add ordinary independent editable copies`() {
        val harness = StarterHarness.create("worked-examples")
        val actualSelection = "fun add(left: Int, right: Int): Int = left + right"
        harness.workspace.project.resolve("source.txt").writeText(actualSelection)
        val examples = WorkedExamples.all
        harness.run { main ->
            main.dismissTrialNotification()
            main.open()
            assertTrue(ideFrame().button("New Template").waitFound(30.seconds).isEnabled())
            assertTrue(ideFrame().button("Import Markdown…").isEnabled())
            assertEquals(emptyList(), packages(harness))
            main.clickButton("Browse Examples…")
            val browser = ui.dialog(title = "Browse Examples").waitFound(30.seconds)
            browser.textField { byAccessibleName("Example introduction") }.text.let {
                assertTrue(it.contains("mock data")); assertTrue(it.contains(harness.workspace.library.toString()))
            }
            for (example in examples) {
                chooseExample(example)
                assertEquals(example.template.markdown, area("Example template Markdown"))
                val tabs = browser.tabbedPane()
                tabs.tab("Mock Inputs").click()
                waitFor("mock inputs tab selected", 10.seconds) { tabs.selectedTabName == "Mock Inputs" }
                assertTrue(area("Example inputs and context").contains("mock values"))
                tabs.tab("Expected Output").click()
                waitFor("expected output tab selected", 10.seconds) { tabs.selectedTabName == "Expected Output" }
                assertEquals(example.expectedOutput, area("Expected example output"))
                tabs.tab("Template Markdown").click()
                waitFor("template tab selected", 10.seconds) { tabs.selectedTabName == "Template Markdown" }
            }
            press(browser.button("Close")); browser.waitNotFound(30.seconds)
            assertEquals(emptyList(), packages(harness))
            assertTrue(ideFrame().button("New Template").isEnabled())
            assertTrue(ideFrame().button("Import Markdown…").isEnabled())

            main.browseExamples(); chooseExample(examples[0]); press(browser.button("Add Example"))
            browser.waitNotFound(30.seconds)
            main.waitForPath(examples[0].template.metadata.name)
            waitFor("selection is required instead of using mock context", 30.seconds) { main.renderedText().contains("{{ide.selection}}") }
            copyToClipboard("missing context sentinel")
            main.clickButton("Copy Prompt")
            main.waitForNotification("Refresh Context")
            assertEquals("missing context sentinel", clipboard())
            openFile("source.txt")
            ideFrame().codeEditorForFile("source.txt").setSelection(0, actualSelection.length)
            main.clickFileAction("Refresh Context")
            val actualReview = StrictPromptRenderer().render(examples[0].template, emptyMap(),
                mapOf("ide.selection" to ContextValue.available(actualSelection))).renderedText
            waitFor("added copy uses actual selected code", 30.seconds) { main.renderedText() == actualReview }
            main.clickButton("Copy Prompt")
            waitFor("review Copy equals actual preview", 10.seconds) { clipboard() == actualReview }

            for ((example, key, value) in listOf(
                Triple(examples[1], "error", "Actual error {{literal}}\nSecond line"),
                Triple(examples[2], "explanation", "The server stores one response for each request ID."),
            )) {
                main.browseExamples(); chooseExample(example); press(browser.button("Add Example"))
                browser.waitNotFound(30.seconds); main.waitForPath(example.template.metadata.name)
                waitFor("example requires supplied text", 30.seconds) { main.renderedText().contains("{{$key}}") }
                val variable = example.template.metadata.variables.single { it.key == key }
                ideFrame().textField { and(byClass("JBTextArea"), byAccessibleName(variable.label)) }.text = value
                val expected = StrictPromptRenderer().render(example.template, mapOf(key to value), emptyMap()).renderedText
                waitFor("typed example input renders with authored defaults", 30.seconds) { main.renderedText() == expected }
                main.clickButton("Copy Prompt")
                waitFor("example Copy uses exact preview", 10.seconds) { clipboard() == expected }
            }
            val review = examples[0]
            main.selectTemplate(review.template.metadata.name)
            main.clickButton("Edit")
            val markdown = ideFrame().editor("//div[@accessiblename='Template Markdown']").waitFound(30.seconds)
            markdown.text = review.template.markdown + "\nPersonal note.\n"
            main.clickButton("Save Template")
            waitFor("edited example is saved", 30.seconds) { ideFrame().button("Save Template").notPresent() }
            main.browseExamples(); chooseExample(review); press(browser.button("Add Example"))
            browser.waitNotFound(30.seconds)
            main.waitForPath("${review.template.metadata.name} (2)")
            waitFor("four independent copies exist", 30.seconds) { packages(harness).size == 4 }
            val codec = TemplateMetadataCodec()
            val copies = packages(harness).associateWith { directory ->
                assertIs<MetadataDecodeResult.Success>(codec.decode(directory.resolve("prompt.meta.json").readText())).metadata
            }
            assertEquals(4, copies.values.map { it.id }.distinct().size)
            assertTrue(copies.values.none { copy -> examples.any { it.template.id.value == copy.id } })
            val first = copies.entries.single { it.value.name == review.template.metadata.name }
            val second = copies.entries.single { it.value.name == "${review.template.metadata.name} (2)" }
            assertNotEquals(first.value.id, second.value.id)
            assertEquals(review.template.markdown + "\nPersonal note.\n", first.key.resolve("prompt.md").readText())
            assertEquals(review.template.markdown, second.key.resolve("prompt.md").readText())
            for (directory in copies.keys) {
                assertEquals(setOf("prompt.md", "prompt.meta.json"), directory.listDirectoryEntries().map { it.fileName.toString() }.toSet())
            }
            assertFalse(second.key.resolve("prompt.meta.json").readText().contains("sampleValues"))
        }
    }

    private fun Driver.chooseExample(example: WorkedExample) {
        val browser = ui.dialog(title = "Browse Examples").waitFound(30.seconds)
        browser.list { byAccessibleName("Worked examples") }.clickItem(example.template.metadata.name)
        waitFor("selected example Markdown shown", 10.seconds) { area("Example template Markdown") == example.template.markdown }
    }

    private fun Driver.area(name: String): String = ui.dialog(title = "Browse Examples")
        .textField { and(byClass("JBTextArea"), byAccessibleName(name)) }.text

    private fun packages(harness: StarterHarness): List<Path> = harness.workspace.library.listDirectoryEntries()
        .filter { it.resolve("prompt.meta.json").isRegularFile() }

    private fun Driver.clipboard(): String? = utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor)

    private fun press(component: UiComponent) {
        component.waitFound(30.seconds)
        waitFor("example action is enabled", 10.seconds) { component.isEnabled() }
        component.setFocus(); component.keyboard { space() }
    }
}
