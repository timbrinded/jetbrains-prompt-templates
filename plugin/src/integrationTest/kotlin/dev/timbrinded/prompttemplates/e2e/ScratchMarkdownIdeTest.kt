package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class ScratchMarkdownIdeTest {
    @Test
    fun `scratch exports the frozen preview to independent native Markdown editors`() = exerciseScratch(false)

    @Test
    fun `scratch remains usable as plain text without Markdown support`() = exerciseScratch(true)

    private fun exerciseScratch(withoutMarkdown: Boolean) {
        val harness = StarterHarness.create("scratch-${if (withoutMarkdown) "plain" else "markdown"}",
            disabledPlugins = if (withoutMarkdown) listOf("org.intellij.plugins.markdown") else emptyList())
        val body = "  {{goal}}\t{{clipboard}}\n{{ide.selection}}\n\n"
        createTemplate(harness, body)
        val source = harness.workspace.project.resolve("source.txt").apply { writeText("selected source\nuntouched\n") }
        harness.run { main ->
            main.dismissTrialNotification()
            assertEquals(!withoutMarkdown, utility(ScratchLanguage::class).findLanguageByID("Markdown") != null)
            val root = scratchRoot()
            val beforeScratches = scratchFiles(root)
            openFile("source.txt")
            val sourceEditor = ideFrame().codeEditorForFile("source.txt")
            val sourceDocument = sourceEditor.document
            sourceEditor.setSelection(0, 15)
            copyToClipboard("captured {{literal}} €")
            main.open().selectTemplate("Scratch review")
            val expected = "  Review\tcaptured {{literal}} €\nselected source\n\n"
            waitFor("preview captures exact whitespace and literal context", 30.seconds) { main.renderedText() == expected }
            val manifest = harness.workspace.templates.manifest()
            assertEquals(beforeScratches, scratchFiles(root)) // Preview alone must not create persistent output.
            copyToClipboard("leave this clipboard unchanged")
            sourceEditor.setSelection(16, 25)
            main.clickFileAction(SCRATCH_ACTION)
            waitFor("first scratch is created", 30.seconds) { (scratchFiles(root) - beforeScratches).size == 1 }
            val first = (scratchFiles(root) - beforeScratches).single()
            val firstEditor = ideFrame().codeEditorForFile(first.fileName.toString()).waitFound(30.seconds)
            waitFor("native editor contains exactly the frozen render", 30.seconds) { firstEditor.text == expected }
            assertEquals(expected, first.readText())
            assertEquals("leave this clipboard unchanged", clipboard())
            assertEquals(source.readText(), withReadAction { sourceDocument.getText() })
            firstEditor.text = "One-off scratch edit\n"
            val firstDocument = firstEditor.document
            assertEquals(expected, main.renderedText())
            assertEquals(manifest, harness.workspace.templates.manifest())
            assertEquals("Review", ideFrame().textField { byAccessibleName("Goal") }.text)
            main.clickFileAction(SCRATCH_ACTION)
            waitFor("repeated export creates another scratch", 30.seconds) { (scratchFiles(root) - beforeScratches).size == 2 }
            val second = (scratchFiles(root) - beforeScratches).single { it != first }
            val secondEditor = ideFrame().codeEditorForFile(second.fileName.toString()).waitFound(30.seconds)
            waitFor("second scratch starts with original invocation text", 30.seconds) { secondEditor.text == expected }
            assertEquals("One-off scratch edit\n", withReadAction { firstDocument.getText() })
            assertEquals("leave this clipboard unchanged", clipboard())
            ideFrame().textField { byAccessibleName("Goal") }.text = ""
            waitFor("empty required input invalidates the render", 30.seconds) { main.renderedText().contains("{{goal}}") }
            main.clickFileAction(SCRATCH_ACTION)
            main.waitForNotification("A value is required for 'goal'.")
            assertEquals(2, (scratchFiles(root) - beforeScratches).size)
            assertEquals(expected, secondEditor.text)
            assertEquals("leave this clipboard unchanged", clipboard())
            assertEquals(manifest, harness.workspace.templates.manifest())
            assertEquals("selected source\nuntouched\n", source.readText())
            ideFrame().textField { byAccessibleName("Goal") }.text = "Review"
            waitFor("original invocation recovers without refreshing context", 30.seconds) { main.renderedText() == expected }
            harness.workspace.evidence.resolve("scratch-files.txt").writeText("$first\n$second\n")
        }
    }

    @Test
    fun `scratch creation failure preserves an obstructing file and the invocation`() {
        val harness = StarterHarness.create("scratch-failure")
        createTemplate(harness, "  Rendered output\n\n")
        harness.run { main ->
            main.dismissTrialNotification()
            main.open().selectTemplate("Scratch review")
            waitFor("literal prompt is ready", 30.seconds) { main.renderedText() == "  Rendered output\n\n" }
            val manifest = harness.workspace.templates.manifest()
            val root = scratchRoot()
            if (root.exists()) {
                assertTrue(root.listDirectoryEntries().isEmpty(), "Failure fixture must not replace existing scratch files")
                root.deleteExisting()
            }
            root.parent.createDirectories()
            root.writeText("Existing file: do not overwrite")
            invokeAction("Synchronize")
            copyToClipboard("failure clipboard sentinel")
            main.clickFileAction(SCRATCH_ACTION)
            val error = ui.dialog(title = "Error").waitFound(30.seconds)
            val ok = error.button("OK").waitFound(30.seconds)
            ok.setFocus(); ok.keyboard { space() }
            error.waitNotFound(30.seconds)
            main.waitForNotification("Could not export the rendered prompt to a scratch Markdown file.")
            assertEquals("Existing file: do not overwrite", root.readText())
            assertEquals("  Rendered output\n\n", main.renderedText())
            assertEquals("failure clipboard sentinel", clipboard())
            assertEquals(manifest, harness.workspace.templates.manifest())
        }
    }

    private fun createTemplate(harness: StarterHarness, body: String) {
        harness.workspace.templates.createTemplate("scratch-review", "Scratch review", TEMPLATE_ID).apply {
            resolve("prompt.md").writeText(body)
            resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
                id = TEMPLATE_ID, name = "Scratch review", variables = listOf(PromptVariable("goal", "Goal", defaultValue = "Review")),
            )))
        }
    }

    private fun Driver.scratchRoot(): Path {
        val paths = utility(ScratchIdePaths::class)
        val config = Path.of(paths.getConfigPath()).toAbsolutePath().normalize()
        val scratch = Path.of(paths.getScratchPath()).resolve("scratches").toAbsolutePath().normalize()
        assertTrue(config.startsWith(Path.of("out/ide-tests/tests").toAbsolutePath().normalize()))
        assertTrue(scratch.startsWith(config), "Scratch output must remain in the isolated IDE configuration")
        return scratch
    }

    private fun scratchFiles(root: Path): Set<Path> = if (!root.exists()) emptySet() else
        root.listDirectoryEntries().filter { it.isRegularFile() }.toSet()

    private fun Driver.clipboard(): String? = utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor)

    companion object {
        private const val SCRATCH_ACTION = "Open Rendered Prompt as Scratch Markdown"
        private const val TEMPLATE_ID = "7f99c67b-d918-4c75-8d4d-94e67e5c8587"
    }
}

@Remote("com.intellij.openapi.application.PathManager")
private interface ScratchIdePaths {
    fun getConfigPath(): String
    fun getScratchPath(): String
}

@Remote("com.intellij.lang.Language")
private interface ScratchLanguage { fun findLanguageByID(id: String): ScratchLanguage? }
