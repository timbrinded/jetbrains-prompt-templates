package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PromptTemplatesIdeTest {
    @Test
    fun `library tree and menus work in an isolated IDE`() {
        val harness = StarterHarness.create("library-tree-and-menus")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Reviews/Security/review-implementation",
            name = "Review implementation",
            id = "d43f3d91-6729-4fb0-bf09-f52c8ce11e59",
        )

        harness.run { ui ->
            val remoteUserHome = utility(RemoteSystem::class).getProperty("user.home")
            assertEquals(harness.workspace.userHome.toString(), remoteUserHome)

            val paths = ui.open().expandAll()
            assertHumanReadablePaths(paths)
            assertPathPresent(paths, "Reviews")
            assertPathPresent(paths, "Reviews/Security")
            assertPathPresent(paths, "Reviews/Security/Review implementation")

            harness.workspace.templates.createTemplate(
                relativeDirectory = "External/watched-template",
                name = "Watched template",
                id = "55a02ddb-33f7-462d-861a-bcf3569577ed",
            )
            ui.waitForPath("External", "Watched template")

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
            ui.waitForVisiblePath("Reviews", "Security", "Review implementation")

            val items = ui.rightClickPath("Reviews")
            val expectedItems = setOf(
                "New Template Here",
                "New Folder Here",
                "Expand Branch",
                "Collapse Branch",
                "Delete Folder…",
            )
            assertTrue(items.containsAll(expectedItems), "Folder menu was $items")
        }
    }

    @Test
    fun `hierarchy mutations work through the real UI`() {
        val harness = StarterHarness.create("hierarchy-mutations")
        harness.workspace.templates.createFolder("Reviews")
        val source = harness.workspace.templates.createTemplate(
            relativeDirectory = "Source/review-pull-request",
            name = "Review pull request",
            id = "68582f11-919c-48d3-bc9a-f87cfd8a119f",
        )
        harness.workspace.templates.createFolder("Destination")
        harness.workspace.templates.createFolder("Parent/Child")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Collision Source/collision-source",
            name = "Collision",
            id = "282e8d89-4362-4649-8aec-5cf2957d2445",
        )
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Collision Destination/collision-existing",
            name = "Collision",
            id = "ab08d45c-2028-43ba-9a8c-fe391f006b8f",
        )
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Trash/Nested/delete-me",
            name = "Delete me",
            id = "031cd891-66f7-485a-a427-981cb1f9ae0a",
        )
        harness.workspace.templates.writeOrder(
            relativeFolder = "",
            folders = listOf(
                "Reviews",
                "Source",
                "Destination",
                "Parent",
                "Collision Source",
                "Collision Destination",
                "Trash",
            ),
            templates = emptyList(),
        )

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

            ui.dragPathOnto(
                sourcePath = listOf("Source", "Review pull request"),
                destinationPath = listOf("Destination"),
            )
            ui.waitForPath("Destination", "Review pull request")
            val destination = harness.workspace.library.resolve("Destination/review-pull-request")
            waitFor("template package is moved on disk", 30.seconds) {
                Files.isRegularFile(destination.resolve("prompt.md")) && Files.notExists(source)
            }
            assertPathPresent(ui.selectedPaths(), "Destination/Review pull request")

            ui.selectContextMenuItem("Source", item = "Move Down")
            waitFor("manual folder order is visible", 30.seconds) {
                appearsBefore(
                    paths = ui.orderedVisiblePaths(),
                    first = "Destination",
                    second = "Source",
                )
            }

            val beforeCycle = harness.workspace.templates.manifest()
            ui.dragPathOnto(
                sourcePath = listOf("Parent"),
                destinationPath = listOf("Parent", "Child"),
            )
            ui.waitForVisibleText("A folder cannot be moved into itself or one of its descendants.")
            assertEquals(beforeCycle, harness.workspace.templates.manifest())

            val beforeCollision = harness.workspace.templates.manifest()
            ui.dragPathOnto(
                sourcePath = listOf("Collision Source", "Collision"),
                destinationPath = listOf("Collision Destination"),
            )
            ui.waitForVisibleText("already exists in the destination folder.")
            assertEquals(beforeCollision, harness.workspace.templates.manifest())

            val trash = harness.workspace.library.resolve("Trash")
            ui.confirmFolderDeletion(folderPath = listOf("Trash"), typedName = "wrong")
            ui.waitForVisibleText("Folder name did not match. Nothing was deleted.")
            assertTrue(Files.isDirectory(trash))

            ui.confirmFolderDeletion(folderPath = listOf("Trash"), typedName = "Trash")
            ui.waitForPathAbsent("Trash")
            assertFalse(Files.exists(trash))
        }
    }

    @Test
    fun `tree state survives an IDE restart`() {
        val harness = StarterHarness.create("tree-state-survives-restart")
        harness.workspace.templates.createTemplate(
            relativeDirectory = "Reviews/Security/review-implementation",
            name = "Review implementation",
            id = "6062e44b-8e83-4e29-b7c5-ce651cc6f9b5",
        )

        harness.runWithRestart(
            beforeRestart = { ui ->
                ui.open().expandAll()
                ui.selectPath("Reviews", "Security", "Review implementation")
            },
            afterRestart = { ui ->
                ui.open()
                assertPathPresent(ui.expandedPaths(), "Reviews/Security")
                assertPathPresent(ui.selectedPaths(), "Reviews/Security/Review implementation")
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
}

@Remote("java.lang.System")
private interface RemoteSystem {
    fun getProperty(key: String): String?
}
