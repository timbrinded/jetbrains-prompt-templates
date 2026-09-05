package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.copyToClipboard
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import dev.timbrinded.prompttemplates.core.PromptVariable
import dev.timbrinded.prompttemplates.core.EnumOption
import dev.timbrinded.prompttemplates.core.PromptVariableType
import dev.timbrinded.prompttemplates.core.TemplateMetadata
import dev.timbrinded.prompttemplates.core.TemplateMetadataCodec
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.junit.jupiter.api.Test

class QuickUseIdeTest {
    @Test
    @Suppress("OPT_IN_USAGE") // Verify the dialog's native mnemonics, independent of IDE keymaps.
    fun `keyboard use captures the invoking selection before the tool window exists`() {
        val harness = StarterHarness.create("quick-keyboard")
        val source = harness.workspace.project.resolve("source.txt").apply { writeText("alpha\nbeta\n") }
        create(harness.workspace.templates, "review", "Review", REVIEW_ID, "{{notes}}\n{{ide.selection}}\n{{clipboard}}",
            listOf(PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE)))
        create(harness.workspace.templates, "plain", "Plain", PLAIN_ID, "Exact no-input prompt\n")
        harness.run { main ->
            main.dismissTrialNotification()
            openFile(source.fileName.toString())
            val editor = ideFrame().codeEditorForFile("source.txt")
            editor.setSelection(0, 5)
            editor.setFocus()
            copyToClipboard("private clipboard sentinel")
            invokeAction("PromptTemplates.Use", component = editor.component)
            val dialog = ui.dialog(title = "Use Prompt Template").waitFound(30.seconds)
            val search = dialog.textField { byAccessibleName("Search templates") }.waitFound()
            search.keyboard { typeText("Plain") }
            waitFor("plain template is selected", 30.seconds) { quickMatches().singleOrNull()?.startsWith("Plain —") == true }
            search.keyboard { enter() }
            waitFor("no-input template is previewed", 30.seconds) { quickPreview() == "Exact no-input prompt\n" }
            assertEquals("private clipboard sentinel", clipboard())
            assertEquals("alpha\nbeta\n", withReadAction { editor.document.getText() })
            closeQuickUse()
            dialog.waitNotFound(30.seconds)
            waitFor("Escape restores the invoking editor focus", 10.seconds) { editor.isFocusOwner() }
            assertTrue(main.libraryTree().notPresent())

            invokeAction("PromptTemplates.Use", component = editor.component)
            dialog.waitFound(30.seconds)
            val input = dialog.textField { byAccessibleName("Search templates") }.waitFound()
            input.keyboard { typeText("Review") }
            waitFor("review template is selected", 30.seconds) { quickMatches().singleOrNull()?.startsWith("Review —") == true }
            input.keyboard { enter() }
            val notes = dialog.x { and(byClass("JBTextArea"), byAccessibleName("Notes")) }.waitFound(30.seconds)
            waitFor("selection and clipboard were captured", 30.seconds) { quickPreview().endsWith("\nalpha\nprivate clipboard sentinel") }
            notes.keyboard { hotKey(KeyEvent.VK_ALT, KeyEvent.VK_C) }
            waitFor("invalid multiline input gets the caret", 10.seconds) { notes.isFocusOwner() }
            assertEquals("private clipboard sentinel", clipboard())
            notes.keyboard { typeText("Private first line"); enter(); typeText("Private second line") }
            val expected = "Private first line\nPrivate second line\nalpha\nprivate clipboard sentinel"
            waitFor("multiline input renders exactly", 30.seconds) { quickPreview() == expected }
            notes.keyboard { hotKey(KeyEvent.VK_ALT, KeyEvent.VK_C) }
            dialog.waitNotFound(30.seconds)
            assertEquals(expected, clipboard())
            assertEquals("alpha\nbeta\n", withReadAction { editor.document.getText() })
            assertTrue(main.libraryTree().notPresent())
        }
        val persisted = persistedSettings(harness)
        assertTrue(persisted.contains(REVIEW_ID))
        assertFalse(persisted.contains(PLAIN_ID), "previewing alone must not record a recent use")
        for (secret in listOf("Private first line", "Private second line", "private clipboard sentinel", "alpha")) {
            assertFalse(persisted.contains(secret), "invocation content must not enter settings")
        }
    }

    @Test
    @Suppress("OPT_IN_USAGE") // Favourite is a native dialog mnemonic, not an IDE action shortcut.
    fun `favourites follow identity and handoff keeps entered values and context`() {
        val harness = StarterHarness.create("quick-handoff-history")
        val original = create(harness.workspace.templates, "a/review", "Review", REVIEW_ID, "{{notes}}: {{clipboard}}",
            listOf(PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE)))
        create(harness.workspace.templates, "b/review", "Review", PLAIN_ID, "Other review")
        val otherRoot = harness.workspace.root.resolve("other-library").createDirectories()
        create(TestLibrary(otherRoot), "review", "Other library", REVIEW_ID, "Other library prompt")
        harness.run { main ->
            main.dismissTrialNotification()
            copyToClipboard("handoff snapshot")
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            val dialog = ui.dialog(title = "Use Prompt Template").waitFound(30.seconds)
            waitFor("duplicate names include distinct paths", 30.seconds) {
                quickMatches().let { it.size == 2 && it[0].contains("a/review") && it[1].contains("b/review") }
            }
            val search = dialog.textField { byAccessibleName("Search templates") }.waitFound()
            search.keyboard { down() }
            val matches = dialog.list { byAccessibleName("Prompt template matches") }
            waitFor("Down navigates without choosing", 10.seconds) { matches.selectedItems.single().contains("b/review") }
            search.keyboard { up(); hotKey(KeyEvent.VK_ALT, KeyEvent.VK_F) }
            waitFor("favourite is marked", 10.seconds) { quickMatches().first().startsWith("★ Review") }
            search.keyboard { enter() }
            val notes = dialog.x { and(byClass("JBTextArea"), byAccessibleName("Notes")) }.waitFound(30.seconds)
            notes.keyboard { typeText("retained value") }
            waitFor("entered value uses the original snapshot", 30.seconds) { quickPreview() == "retained value: handoff snapshot" }
            copyToClipboard("changed after preview")
            dialog.button("Open in Tool Window").setFocus()
            dialog.button("Open in Tool Window").keyboard { space() }
            dialog.waitNotFound(30.seconds)
            waitFor("first tool-window creation preserves the exact invocation", 30.seconds) { main.renderedText() == "retained value: handoff snapshot" }
            main.clickButton("Copy Prompt")
            assertEquals("retained value: handoff snapshot", clipboard())

            val moved = original.moveTo(harness.workspace.library.resolve("renamed"))
            moved.resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(
                id = REVIEW_ID, name = "Renamed", variables = listOf(PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE)),
            )))
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            dialog.waitFound(30.seconds)
            waitFor("renamed favourite resolves by UUID", 30.seconds) { quickMatches().firstOrNull()?.startsWith("★ Renamed — renamed") == true }
            closeQuickUse()
            dialog.waitNotFound(30.seconds)
            main.changeLibrary(otherRoot)
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            dialog.waitFound(30.seconds)
            waitFor("other library does not inherit the same UUID's favourite", 30.seconds) {
                quickMatches().singleOrNull()?.startsWith("Other library —") == true
            }
            closeQuickUse()
            dialog.waitNotFound(30.seconds)
            main.changeLibrary(harness.workspace.library)
            moved.resolve("prompt.md").deleteExisting()
            moved.resolve("prompt.meta.json").deleteExisting()
            moved.deleteExisting()
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            dialog.waitFound(30.seconds)
            waitFor("missing history identity is ignored", 30.seconds) { quickMatches().singleOrNull()?.contains("b/review") == true }
            closeQuickUse()
        }
        val persisted = persistedSettings(harness)
        assertTrue(persisted.contains(REVIEW_ID))
        assertFalse(persisted.contains("retained value"))
        assertFalse(persisted.contains("handoff snapshot"))
    }

    @Test
    fun `existing tool window and Quick Use controls follow the same invocation values`() {
        val harness = StarterHarness.create("quick-shared-controls")
        create(harness.workspace.templates, "review", "Review", REVIEW_ID,
            "{{goal}}|{{notes}}|{{mode}}|{{clipboard}}", listOf(
                PromptVariable("goal", "Goal", defaultValue = "Initial goal"),
                PromptVariable("notes", "Notes", type = PromptVariableType.MULTILINE, defaultValue = "Initial notes"),
                PromptVariable("mode", "Mode", type = PromptVariableType.ENUM, defaultValue = "quick",
                    options = listOf(EnumOption("quick", "Quick", "Quick"), EnumOption("deep", "Deep", "Deep"))),
            ))
        harness.run { main ->
            main.dismissTrialNotification()
            main.open().selectTemplate("Review")
            copyToClipboard("shared snapshot")
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            val dialog = ui.dialog(title = "Use Prompt Template").waitFound(30.seconds)
            waitFor("review candidate is loaded", 30.seconds) { quickMatches().singleOrNull()?.startsWith("Review —") == true }
            dialog.textField { byAccessibleName("Search templates") }.keyboard { enter() }
            val goal = dialog.textField { and(byClass("JBTextField"), byAccessibleName("Goal")) }.waitFound(30.seconds)
            val notes = dialog.textField { and(byClass("JBTextArea"), byAccessibleName("Notes")) }.waitFound(30.seconds)
            goal.text = "Quick goal"
            notes.text = "Quick\nnotes"
            dialog.comboBox { and(byClass("ComboBox"), byAccessibleName("Mode")) }.selectItem("Deep")
            val expected = "Quick goal|Quick\nnotes|Deep|shared snapshot"
            waitFor("Quick Use renders all changed input types", 30.seconds) { quickPreview() == expected }
            dialog.button("Copy Prompt").setFocus()
            dialog.button("Copy Prompt").keyboard { space() }
            dialog.waitNotFound(30.seconds)
            waitFor("Copy closes Quick Use with the shared payload", 10.seconds) { clipboard() == expected && main.renderedText() == expected }
            val panel = ideFrame().x { byClass("PromptTemplatesPanel") }
            fun frameGoal() = panel.textField { and(byClass("JBTextField"), byAccessibleName("Goal")) }
            fun frameNotes() = panel.textField { and(byClass("JBTextArea"), byAccessibleName("Notes")) }
            fun frameMode() = panel.comboBox { and(byClass("ComboBox"), byAccessibleName("Mode")) }
            assertEquals("Quick goal", frameGoal().text)
            assertEquals("Quick\nnotes", frameNotes().text)
            assertEquals("Deep", frameMode().getSelectedItem())

            copyToClipboard("second snapshot")
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            dialog.waitFound(30.seconds)
            waitFor("review candidate is loaded again", 30.seconds) { quickMatches().singleOrNull()?.contains("Review —") == true }
            dialog.textField { byAccessibleName("Search templates") }.keyboard { enter() }
            val reopenedGoal = dialog.textField { and(byClass("JBTextField"), byAccessibleName("Goal")) }.waitFound(30.seconds)
            val reopenedNotes = dialog.textField { and(byClass("JBTextArea"), byAccessibleName("Notes")) }
            frameGoal().text = "Tool-window goal"
            waitFor("Quick Use reflects a tool-window edit", 30.seconds) { reopenedGoal.text == "Tool-window goal" }
            main.clickFileAction("Reset Values to Defaults")
            waitFor("both forms reset without refreshing context", 30.seconds) {
                reopenedGoal.text == "Initial goal" && reopenedNotes.text == "Initial notes" &&
                    quickPreview() == "Initial goal|Initial notes|Quick|second snapshot"
            }
            dialog.button("Open in Tool Window").setFocus()
            dialog.button("Open in Tool Window").keyboard { space() }
            dialog.waitNotFound(30.seconds)
            assertEquals("Initial goal", frameGoal().text)
            assertEquals("Initial notes", frameNotes().text)
            assertEquals("Quick", frameMode().getSelectedItem())
            assertEquals("Initial goal|Initial notes|Quick|second snapshot", main.renderedText())
        }
    }

    @Test
    fun `large library search is measured in the supported IDE`() {
        val harness = StarterHarness.create("quick-search-benchmark")
        repeat(500) { index ->
            create(harness.workspace.templates, "group-${index % 10}/template-$index", "Template ${index.toString().padStart(3, '0')}",
                "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "Body marker-$index\n" + "Benchmark text. ".repeat(100))
        }
        harness.run { main ->
            main.dismissTrialNotification()
            val start = TimeSource.Monotonic.markNow()
            invokeAction("PromptTemplates.Use", component = ideFrame().component)
            val dialog = ui.dialog(title = "Use Prompt Template").waitFound(30.seconds)
            waitFor("500 templates loaded", 30.seconds) { quickMatches().size == 500 }
            val cold = start.elapsedNow()
            val search = dialog.textField { byAccessibleName("Search templates") }.waitFound()
            val timings = listOf("Template 499", "marker-123", "group-7").map { query ->
                val queryStart = TimeSource.Monotonic.markNow()
                search.text = query
                waitFor("ranked results for $query", 30.seconds) {
                    val rows = quickMatches()
                    when (query) {
                        "Template 499" -> rows.singleOrNull()?.startsWith("Template 499 —") == true
                        "marker-123" -> rows.singleOrNull()?.startsWith("Template 123 —") == true
                        else -> rows.size == 50 && rows.all { it.contains("group-7/") }
                    }
                }
                "$query=${queryStart.elapsedNow()}"
            }
            harness.workspace.evidence.resolve("quick-use-benchmark.txt").writeText(
                "WebStorm 262.8665.259; 500 templates; 1.5 KB bodies; Driver-observed latency includes RPC and polling.\n" +
                    "cold-open=$cold\n" + timings.joinToString("\n", postfix = "\n"),
            )
            closeQuickUse()
            dialog.waitNotFound(30.seconds)
        }
    }

    private fun create(library: TestLibrary, path: String, name: String, id: String, body: String,
                       variables: List<PromptVariable> = emptyList()): Path = library.createTemplate(path, name, id).also { directory ->
        directory.resolve("prompt.md").writeText(body)
        directory.resolve("prompt.meta.json").writeText(TemplateMetadataCodec().encode(TemplateMetadata(id = id, name = name, variables = variables)))
    }

    private fun Driver.quickMatches(): List<String> = ui.dialog(title = "Use Prompt Template")
        .list { byAccessibleName("Prompt template matches") }.items

    private fun Driver.quickPreview(): String = withContext(OnDispatcher.EDT) {
        ui.dialog(title = "Use Prompt Template").editor("//div[@accessiblename='Quick Use preview']").waitFound(30.seconds).text
    }

    private fun Driver.clipboard(): String? = utility(IdeClipboard::class).getInstance().getContents(DataFlavor.stringFlavor)

    private fun Driver.closeQuickUse() {
        val close = ui.dialog(title = "Use Prompt Template").button("Close").waitFound()
        close.setFocus()
        waitFor("dialog control owns keyboard focus before Escape", 10.seconds) { close.isFocusOwner() }
        close.keyboard { escape() }
    }

    private fun persistedSettings(harness: StarterHarness): String {
        val config = harness.workspace.evidence.resolve("isolated-paths.txt").readText().lineSequence()
            .first { it.startsWith("starter.config=") }.substringAfter('=')
        return Path.of(config).resolve("options/promptTemplates.xml").readText()
    }

    companion object {
        private const val REVIEW_ID = "e0a69be8-ed5c-4352-bdd1-841eb8d1a460"
        private const val PLAIN_ID = "aaf1656f-282d-46e8-8f04-1e53c5b9c49a"
    }
}
