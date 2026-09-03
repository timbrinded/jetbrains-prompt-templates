package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PromptTemplatesIdeTest {
    // Single end-to-end smoke: real FS + real UI wiring. Unit suites own selection/menu/state coverage.
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
                harness.workspace.library.resolve("Reviews/Security/threat-model/prompt.md").isRegularFile(),
            )
            assertTrue(
                harness.workspace.library.resolve("Reviews/Security/threat-model/prompt.meta.json").isRegularFile(),
            )

            ui.movePathToFolder(
                sourcePath = listOf("Source", "Review pull request"),
                destinationPath = "Destination",
            )
            ui.waitForPath("Destination", "Review pull request")
            val destination = harness.workspace.library.resolve("Destination/review-pull-request")
            waitFor("template package is moved on disk", 30.seconds) {
                destination.resolve("prompt.md").isRegularFile() && source.notExists()
            }
            assertPathPresent(ui.selectedPaths(), "Destination/Review pull request")

            val beforeCollision = harness.workspace.templates.manifest()
            ui.movePathToFolder(
                sourcePath = listOf("Collision Source", "Collision"),
                destinationPath = "Collision Destination",
            )
            ui.waitForAccessibleText("already exists in the destination folder.")
            assertEquals(beforeCollision, harness.workspace.templates.manifest())

            val trash = harness.workspace.library.resolve("Trash")
            ui.confirmFolderDeletion(folderPath = listOf("Trash"), typedName = "Trash")
            ui.waitForPathAbsent("Trash")
            assertFalse(trash.exists())
        }
    }

    private fun assertPathPresent(paths: List<String>, suffix: String) {
        assertTrue(paths.any { path -> path.endsWithLibraryPath(suffix) }, "Expected '$suffix' in $paths")
    }
}
