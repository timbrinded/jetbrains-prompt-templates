package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PromptTemplatesIdeTest {
    @Test
    fun `tool window opens in an isolated IDE and shows nested templates`() {
        val harness = StarterHarness.create("tool-window-opens-in-isolated-ide")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Reviews/Security/review-implementation",
            name = "Review implementation",
            id = "d43f3d91-6729-4fb0-bf09-f52c8ce11e59",
            supportFiles = mapOf("examples/context.txt" to "Preserve support files during moves.\n"),
        )

        harness.run { ui ->
            val remoteUserHome = utility(RemoteSystem::class).getProperty("user.home")
            assertEquals(harness.workspace.userHome.toString(), remoteUserHome)

            val paths = ui.open().expandAll()
            assertHumanReadablePaths(paths)
            assertPathPresent(paths, "Reviews")
            assertPathPresent(paths, "Reviews/Security")
            assertPathPresent(paths, "Reviews/Security/Review implementation")
        }
    }

    @Test
    fun `folder context menu exposes hierarchy operations`() {
        val harness = StarterHarness.create("folder-context-menu-exposes-hierarchy-operations")
        harness.workspace.templates.createFolder("Ideas")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Reviews/review-pull-request",
            name = "Review pull request",
            id = "8baeb1b3-5e2f-4fb9-8d33-3f4d9c4ba418",
        )
        harness.workspace.templates.writeOrder(
            relativeFolder = "",
            folders = listOf("Reviews", "Ideas"),
            templates = emptyList(),
        )

        harness.run { ui ->
            ui.open().expandAll()
            val items = ui.rightClickPath("Reviews")
            assertTrue("New Template Here" in items, "Folder menu was $items")
            assertTrue("New Folder Here" in items, "Folder menu was $items")
            assertTrue("Expand Branch" in items, "Folder menu was $items")
            assertTrue("Collapse Branch" in items, "Folder menu was $items")
            assertTrue("Delete Folder…" in items, "Folder menu was $items")
        }
    }

    @Test
    fun `root context menu collapses and expands the complete tree`() {
        val harness = StarterHarness.create("root-context-menu-collapse-and-expand")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Reviews/Security/review-implementation",
            name = "Review implementation",
            id = "5584307e-43f7-4e05-a19f-454b6f354b7d",
        )

        harness.run { ui ->
            ui.open().expandAll()
            ui.waitForVisiblePath("Reviews", "Security", "Review implementation")
            assertTrue(
                ui.expandedRowCount() >= 2,
                "Expected nested folders to be expanded before Collapse All.",
            )

            ui.selectRootContextMenuItem("Collapse All")
            waitFor("all tree rows are collapsed", 30.seconds) {
                ui.expandedRowCount() == 0
            }
            ui.waitForVisiblePathAbsent("Reviews", "Security")

            ui.selectRootContextMenuItem("Expand All")
            waitFor("nested folder rows are expanded", 30.seconds) {
                ui.expandedRowCount() >= 2
            }
            ui.waitForVisiblePath("Reviews", "Security", "Review implementation")
        }
    }

    @Test
    fun `creates nested folders and templates through the real UI`() {
        val harness = StarterHarness.create("creates-nested-folders-and-templates")
        harness.workspace.templates.createFolder("Reviews")

        harness.run { ui ->
            ui.open()
            ui.createFolder(parentPath = listOf("Reviews"), name = "Security")
            ui.createDefaultTemplate(
                parentPath = listOf("Reviews", "Security"),
                name = "Threat model",
            )

            assertTrue(
                Files.isRegularFile(harness.workspace.library.resolve("Reviews/Security/threat-model/prompt.md")),
            )
            assertTrue(
                Files.isRegularFile(harness.workspace.library.resolve("Reviews/Security/threat-model/prompt.meta.json")),
            )
        }
    }

    @Test
    fun `physical drag moves a template and preserves its package`() {
        val harness = StarterHarness.create("physical-drag-moves-template")
        val source = harness.workspace.templates.createTemplate(
            relativeDirectory = "Source/review-pull-request",
            name = "Review pull request",
            id = "68582f11-919c-48d3-bc9a-f87cfd8a119f",
            supportFiles = mapOf("examples/request.txt" to "Keep this byte-for-byte.\n"),
        )
        harness.workspace.templates.createFolder("Destination")
        harness.workspace.templates.writeOrder(
            relativeFolder = "",
            folders = listOf("Source", "Destination"),
            templates = emptyList(),
        )
        val expectedMarkdown = source.resolve("prompt.md").readBytes()
        val expectedMetadata = source.resolve("prompt.meta.json").readBytes()
        val expectedSupportFile = source.resolve("examples/request.txt").readBytes()

        harness.run { ui ->
            ui.open().expandAll()
            ui.dragPathOnto(
                sourcePath = listOf("Source", "Review pull request"),
                destinationPath = listOf("Destination"),
            )
            ui.waitForPath("Destination", "Review pull request")

            val destination = harness.workspace.library.resolve("Destination/review-pull-request")
            waitFor("template package is moved on disk", 30.seconds) {
                Files.isRegularFile(destination.resolve("prompt.md")) && Files.notExists(source)
            }
            assertContentEquals(expectedMarkdown, destination.resolve("prompt.md").readBytes())
            assertContentEquals(expectedMetadata, destination.resolve("prompt.meta.json").readBytes())
            assertContentEquals(expectedSupportFile, destination.resolve("examples/request.txt").readBytes())
            assertTrue(
                ui.selectedPaths().any { it.contains("Review pull request", ignoreCase = true) },
                "Moved template was not selected: ${ui.selectedPaths()}",
            )

            ui.selectContextMenuItem("Source", item = "Move Down")
            waitFor("manual folder order is visible", 30.seconds) {
                appearsBefore(
                    paths = ui.orderedVisiblePaths(),
                    first = "Destination",
                    second = "Source",
                )
            }
        }
    }

    @Test
    fun `unsafe drag moves are rejected without filesystem changes`() {
        val harness = StarterHarness.create("unsafe-drag-moves-are-rejected")
        harness.workspace.templates.createFolder("Parent/Child")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Source/collision-source",
            name = "Collision",
            id = "282e8d89-4362-4649-8aec-5cf2957d2445",
        )
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Destination/collision-existing",
            name = "Collision",
            id = "ab08d45c-2028-43ba-9a8c-fe391f006b8f",
        )

        harness.run { ui ->
            ui.open().expandAll()
            val beforeCycle = harness.workspace.templates.manifest()
            ui.dragPathOnto(
                sourcePath = listOf("Parent"),
                destinationPath = listOf("Parent", "Child"),
            )
            ui.waitForVisibleText("A folder cannot be moved into itself or one of its descendants.")
            assertEquals(beforeCycle, harness.workspace.templates.manifest())

            val beforeCollision = harness.workspace.templates.manifest()
            ui.dragPathOnto(
                sourcePath = listOf("Source", "Collision"),
                destinationPath = listOf("Destination"),
            )
            ui.waitForVisibleText("already exists in the destination folder.")
            assertEquals(beforeCollision, harness.workspace.templates.manifest())
        }
    }

    @Test
    fun `recursive folder deletion requires the exact typed name`() {
        val harness = StarterHarness.create("recursive-folder-deletion-requires-name")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Trash/Nested/delete-me",
            name = "Delete me",
            id = "031cd891-66f7-485a-a427-981cb1f9ae0a",
        )
        val trash = harness.workspace.library.resolve("Trash")

        harness.run { ui ->
            ui.open().expandAll()
            ui.confirmFolderDeletion(folderPath = listOf("Trash"), typedName = "wrong")
            ui.waitForVisibleText("Folder name did not match. Nothing was deleted.")
            assertTrue(Files.isDirectory(trash))

            ui.confirmFolderDeletion(folderPath = listOf("Trash"), typedName = "Trash")
            ui.waitForPathAbsent("Trash")
            assertFalse(Files.exists(trash))
        }
    }

    @Test
    fun `search watcher and selection survive an IDE restart`() {
        val harness = StarterHarness.create("search-watcher-and-restart")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Reviews/Security/review-implementation",
            name = "Review implementation",
            id = "6062e44b-8e83-4e29-b7c5-ce651cc6f9b5",
        )

        harness.runWithRestart(
            beforeRestart = { ui ->
                ui.open().expandAll()
                ui.selectPath("Reviews", "Security", "Review implementation")

                harness.workspace.templates.createTemplate(
                    relativeDirectory = "External/watched-template",
                    name = "Watched template",
                    id = "55a02ddb-33f7-462d-861a-bcf3569577ed",
                )
                ui.waitForPath("External", "Watched template")

                ui.search("External/watched-template")
                ui.waitForVisiblePath("External", "Watched template")
                ui.waitForVisiblePathAbsent("Reviews", "Security", "Review implementation")
                ui.clearSearch()
                ui.waitForVisiblePath("Reviews", "Security", "Review implementation")
                assertTrue(
                    ui.selectedPaths().any { it.contains("Review implementation", ignoreCase = true) },
                    "Template selection was not restored after clearing search: ${ui.selectedPaths()}",
                )
            },
            afterRestart = { ui ->
                ui.open()
                assertTrue(
                    ui.expandedPaths().any { it.endsWithLibraryPath("Reviews/Security") },
                    "Folder expansion was not restored: ${ui.expandedPaths()}",
                )
                assertTrue(
                    ui.selectedPaths().any { it.contains("Review implementation", ignoreCase = true) },
                    "Template selection was not restored: ${ui.selectedPaths()}",
                )
            },
        )
    }

    private fun assertPathPresent(paths: List<String>, suffix: String) {
        assertTrue(
            paths.any { path -> path.endsWithLibraryPath(suffix) },
            "Expected a tree path ending in '$suffix', but found $paths",
        )
    }

    private fun assertHumanReadablePaths(paths: List<String>) {
        val internalPaths = paths.filter { path ->
            path.split('/').any { segment ->
                segment.contains("Folder(entry=") || segment.contains("Template(entry=")
            }
        }
        assertTrue(
            internalPaths.isEmpty(),
            "Tree rows exposed internal model text instead of human labels: $internalPaths",
        )
    }

    private fun appearsBefore(paths: List<String>, first: String, second: String): Boolean {
        val firstIndex = paths.indexOfFirst { path -> path.endsWithLibraryPath(first) }
        val secondIndex = paths.indexOfFirst { path -> path.endsWithLibraryPath(second) }
        return firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex
    }

    private fun String.endsWithLibraryPath(expectedPath: String): Boolean {
        val actualSegments = split('/')
        val expectedSegments = expectedPath.split('/')
        if (actualSegments.size < expectedSegments.size) return false
        return actualSegments.takeLast(expectedSegments.size)
            .zip(expectedSegments)
            .all { (actual, expected) -> actual.contains(expected, ignoreCase = true) }
    }
}

@Remote("java.lang.System")
private interface RemoteSystem {
    fun getProperty(key: String): String?
}
