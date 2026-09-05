package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.FileChooserDialogUi
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class ContextAttachmentsIdeTest {
    @Test
    fun `file capture works without Git and snapshots unsaved text through refresh removal and failures`() {
        val harness = StarterHarness.create("file-attachments", disabledPlugins = listOf("Git4Idea", "org.intellij.qodana", "com.intellij.settingsSync", "org.jetbrains.plugins.github", "org.jetbrains.plugins.gitlab"))
        val directory = createTemplate(harness)
        val source = harness.workspace.project.resolve("source.txt").apply { writeText("On disk\n") }
        val removable = harness.workspace.project.resolve("removable.txt").apply { writeText("Captured closed file") }
        val binary = harness.workspace.project.resolve("binary.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 0)) }
        val large = harness.workspace.project.resolve("large.txt").apply { writeText("x".repeat(256 * 1024 + 1)) }
        harness.run { main ->
            main.dismissTrialNotification()
            openFile("source.txt")
            val editor = ideFrame().codeEditorForFile("source.txt")
            val original = "  Unsaved secret {{unknown}}\n`````\nlast  "
            editor.text = original
            main.open().selectTemplate("Attachment review")
            val before = harness.workspace.templates.manifest()
            main.clickFileAction("Add Context…")
            val dialog = ui.dialog(title = "Add Context").waitFound(30.seconds)
            press(dialog.button("Git Diff…"))
            dialog.waitContainsText("Git diff capture requires the Git plugin")
            press(dialog.button("Current File"))
            waitFor("current buffer captured", 30.seconds) { capturedText() == original }
            assertTrue(provenance().contains("editor buffer (unsaved)"))
            assertTrue(provenance().contains(source.toString()))
            press(dialog.button("Cancel"))
            dialog.waitNotFound(30.seconds)
            assertTrue(main.renderedText().contains("{{ide.attachments}}"))
            main.clickFileAction("Add Context…")
            dialog.waitFound(30.seconds)
            press(dialog.button("Current File"))
            waitFor("buffer captured for apply", 30.seconds) { capturedText() == original }
            press(dialog.button("Apply Attachments"))
            dialog.waitNotFound(30.seconds)
            waitFor("attachment reaches the inspected render", 30.seconds) { main.renderedText().contains(original) }
            val frozen = main.renderedText()
            main.clickButton("Copy Prompt")
            waitFor("copy delivers exact attachment render", 10.seconds) { clipboard() == frozen }
            editor.text = "Later unsaved content"
            main.clickFileAction("Refresh Context")
            waitFor("normal context refresh finishes", 10.seconds) { ideFrame().button("Copy Prompt").isEnabled() }
            assertEquals(frozen, main.renderedText())
            main.clickFileAction("Add Context…")
            dialog.waitFound(30.seconds)
            press(dialog.button("Refresh Selected"))
            waitFor("explicit attachment refresh reads the new buffer", 30.seconds) { capturedText() == "Later unsaved content" }
            press(dialog.button("Apply Attachments"))
            dialog.waitNotFound(30.seconds)
            waitFor("explicit refresh updates the render", 10.seconds) { main.renderedText().contains("Later unsaved content") }
            main.clickFileAction("Add Context…")
            dialog.waitFound(30.seconds)
            selectFile(binary)
            dialog.waitContainsText("Binary")
            assertEquals(1, dialog.list().items.size)
            selectFile(large)
            dialog.waitContainsText("256 KiB")
            assertEquals(1, dialog.list().items.size)
            assertEquals("Later unsaved content", capturedText())
            selectFile(removable)
            waitFor("closed file is captured", 30.seconds) { capturedText() == "Captured closed file" }
            assertTrue(provenance().contains("on-disk text"))
            removable.deleteExisting()
            press(dialog.button("Refresh Selected"))
            dialog.waitContainsText("missing")
            assertEquals("Captured closed file", capturedText())
            press(dialog.button("Remove"))
            assertEquals(1, dialog.list().items.size)
            press(dialog.button("Remove"))
            assertEquals(0, dialog.list().items.size)
            press(dialog.button("Cancel"))
            dialog.waitNotFound(30.seconds)
            assertTrue(main.renderedText().contains("Later unsaved content"))
            main.clickFileAction("Add Context…")
            dialog.waitFound(30.seconds)
            press(dialog.button("Remove"))
            press(dialog.button("Apply Attachments"))
            dialog.waitNotFound(30.seconds)
            waitFor("removing last item blocks required context", 10.seconds) { main.renderedText().contains("{{ide.attachments}}") }
            editor.setFocus()
            invokeAction("PromptTemplates.Use", component = editor.component)
            val quick = ui.dialog(title = "Use Prompt Template").waitFound(30.seconds)
            val search = quick.textField { byAccessibleName("Search templates") }
            search.keyboard { typeText("Attachment review"); enter() }
            press(quick.button("Add Context…"))
            dialog.waitFound(30.seconds)
            press(dialog.button("Current File"))
            waitFor("Quick Use captures current buffer", 30.seconds) { capturedText() == "Later unsaved content" }
            press(dialog.button("Apply Attachments"))
            dialog.waitNotFound(30.seconds)
            val quickPreview = quick.editor("//div[@accessiblename='Quick Use preview']").waitFound(30.seconds)
            waitFor("Quick Use shows attachment render", 30.seconds) { quickPreview.text.contains("Later unsaved content") }
            val quickPayload = quickPreview.text
            press(quick.button("Open in Tool Window"))
            quick.waitNotFound(30.seconds)
            assertEquals(quickPayload, main.renderedText())
            main.clickButton("Copy Prompt")
            waitFor("handoff retains exact attachment payload", 10.seconds) { clipboard() == quickPayload }
            assertEquals(before, harness.workspace.templates.manifest())
            assertEquals("Context:\n{{ide.attachments}}", directory.resolve("prompt.md").readText())
        }
        val config = harness.workspace.evidence.resolve("isolated-paths.txt").readText().lineSequence()
            .first { it.startsWith("starter.config=") }.substringAfter('=')
        assertFalse(Path.of(config).resolve("options/promptTemplates.xml").readText().contains("Unsaved secret"))
    }

    @Test
    fun `Git capture requires an explicit root and scope and keeps staged and unstaged payloads separate`() {
        val harness = StarterHarness.create("git-attachments")
        createTemplate(harness)
        val repoA = harness.workspace.project.resolve("repo-a").createDirectories()
        val repoB = harness.workspace.project.resolve("repo-b").createDirectories()
        for ((repo, marker) in listOf(repoA to "ALPHA", repoB to "BRAVO")) {
            git(repo, "init", "--initial-branch=main")
            git(repo, "config", "user.name", "IDE test")
            git(repo, "config", "user.email", "ide-test@example.invalid")
            repo.resolve("review.txt").writeText("$marker baseline\n")
            git(repo, "add", "review.txt")
            git(repo, "commit", "-m", "Baseline")
            repo.resolve("review.txt").writeText("$marker staged\n")
            git(repo, "add", "review.txt")
            repo.resolve("review.txt").writeText("$marker unstaged\n")
            repo.resolve("untracked.txt").writeText("Do not scrape this file")
        }
        // A required failing clean filter must not run during the plugin's read-only diff capture.
        git(repoB, "config", "filter.tripwire.clean", "false")
        git(repoB, "config", "filter.tripwire.required", "true")
        repoB.resolve(".gitattributes").writeText("*.txt filter=tripwire\n")
        val headB = git(repoB, "rev-parse", "HEAD").trim()
        harness.workspace.project.resolve(".idea").createDirectories().resolve("vcs.xml").writeText(
            """<project version="4"><component name="VcsDirectoryMappings"><mapping directory="$repoA" vcs="Git"/><mapping directory="$repoB" vcs="Git"/></component></project>""",
        )
        harness.run { main ->
            main.open().selectTemplate("Attachment review")
            main.clickFileAction("Add Context…")
            val dialog = ui.dialog(title = "Add Context").waitFound(30.seconds)
            fun captureDiff(repo: Path, scope: String) {
                press(dialog.button("Git Diff…"))
                val choice = ui.dialog(title = "Capture Git Diff").waitFound(30.seconds)
                val repository = choice.comboBox { and(byClass("ComboBox"), byAccessibleName("Repository:")) }
                val scopeField = choice.comboBox { and(byClass("ComboBox"), byAccessibleName("Scope:")) }
                assertEquals(-1, withContext(OnDispatcher.EDT) { cast(repository.component, AttachmentComboSelection::class).getSelectedIndex() })
                assertEquals(-1, withContext(OnDispatcher.EDT) { cast(scopeField.component, AttachmentComboSelection::class).getSelectedIndex() })
                repository.selectItemContains(repo.toString())
                assertTrue(repository.getSelectedItem().orEmpty().contains(repo.toString()))
                scopeField.selectItem(scope)
                press(choice.button("Capture Diff"))
                choice.waitNotFound(30.seconds)
            }
            captureDiff(repoB, "Staged: HEAD → index")
            waitFor("staged BRAVO diff captured", 30.seconds) { capturedText().contains("+BRAVO staged") }
            assertFalse(capturedText().contains("ALPHA"))
            assertFalse(capturedText().contains("BRAVO unstaged"))
            assertFalse(capturedText().contains("Do not scrape"))
            assertTrue(provenance().contains("base HEAD $headB"))
            captureDiff(repoB, "Unstaged: index → working tree")
            waitFor("unstaged diff uses index as base", 30.seconds) { capturedText().contains("-BRAVO staged") && capturedText().contains("+BRAVO unstaged") }
            assertTrue(provenance().contains("base index; repository HEAD $headB"))
            assertEquals(2, dialog.list().items.size)
            captureDiff(repoA, "Staged: HEAD → index")
            waitFor("explicit alternate root captures ALPHA", 30.seconds) { capturedText().contains("+ALPHA staged") }
            assertEquals(3, dialog.list().items.size)
            press(dialog.button("Apply Attachments"))
            dialog.waitNotFound(30.seconds)
            val frozen = main.renderedText()
            main.clickButton("Copy Prompt")
            waitFor("all diff blocks match Copy", 10.seconds) { clipboard() == frozen }
            repoB.resolve("review.txt").writeText("BRAVO later disk state\n")
            assertEquals(frozen, main.renderedText())
            main.clickFileAction("Add Context…")
            dialog.waitFound(30.seconds)
            captureDiff(repoB, "Unstaged: index → working tree")
            waitFor("recapture replaces same source in place", 30.seconds) { capturedText().contains("+BRAVO later disk state") }
            assertEquals(3, dialog.list().items.size)
            repoB.resolve("binary.bin").writeBytes(byteArrayOf(0, 1, 2, 0))
            git(repoB, "add", "binary.bin")
            captureDiff(repoB, "Staged: HEAD → index")
            dialog.waitContainsText("binary or submodule")
            assertEquals(3, dialog.list().items.size)
            repoB.resolve("review.txt").writeText("x".repeat(300 * 1024))
            captureDiff(repoB, "Unstaged: index → working tree")
            dialog.waitContainsText("exceeds the capture limit")
            assertEquals(3, dialog.list().items.size)
            press(dialog.button("Cancel"))
            dialog.waitNotFound(30.seconds)
            assertEquals(frozen, main.renderedText())
        }
    }

    private fun createTemplate(harness: StarterHarness): Path = harness.workspace.templates.createTemplate(
        "review", "Attachment review", "299c0a9e-ed13-4dfe-8e08-18f843d9f65c",
    ).apply {
        resolve("prompt.md").writeText("Context:\n{{ide.attachments}}")
        resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
            id = "299c0a9e-ed13-4dfe-8e08-18f843d9f65c", name = "Attachment review",
        )))
    }

    private fun Driver.capturedText(): String = ui.dialog(title = "Add Context")
        .textField { and(byClass("JBTextArea"), byAccessibleName("Captured attachment text")) }.text

    private fun Driver.provenance(): String = ui.dialog(title = "Add Context")
        .textField { and(byClass("JBTextArea"), byAccessibleName("Attachment provenance")) }.text

    private fun Driver.clipboard(): String? = utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor)

    private fun press(component: UiComponent) { component.waitFound(30.seconds).setFocus(); component.keyboard { space() } }

    private fun Driver.selectFile(path: Path) {
        press(ui.dialog(title = "Add Context").button("Selected Files…"))
        val chooser = ui.x("//div[@title='Select Context Files']", FileChooserDialogUi::class.java).waitFound(30.seconds)
        waitFor("selected source appears in the chooser tree", 30.seconds) {
            chooser.fileTree.collectExpandedPaths().any { it.path.lastOrNull() == path.fileName.toString() }
        }
        val row = chooser.fileTree.collectExpandedPaths().single { it.path.lastOrNull() == path.fileName.toString() }.row
        chooser.fileTree.fixture.selectRow(row)
        press(chooser.okButton)
        chooser.waitNotFound(30.seconds)
    }

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Fixture Git command failed: $output" }
        return output
    }
}

@Remote("javax.swing.JComboBox")
private interface AttachmentComboSelection { fun getSelectedIndex(): Int }
