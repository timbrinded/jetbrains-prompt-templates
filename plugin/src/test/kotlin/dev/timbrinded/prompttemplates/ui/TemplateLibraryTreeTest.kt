package dev.timbrinded.prompttemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.core.TemplateSummary
import java.nio.file.Path
import java.util.UUID
import javax.accessibility.AccessibleContext
import javax.swing.tree.DefaultTreeCellRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateLibraryTreeTest {
    private val root = Path.of("/library")

    @Test
    fun `search retains ancestors and matches folder paths`() {
        val security = folder(
            "Reviews/Security",
            listOf(template("Reviews/Security/audit", "Audit a change", "check permissions")),
        )
        val reviews = folder("Reviews", listOf(security))
        val ideas = folder("Ideas", listOf(template("Ideas/brainstorm", "Brainstorm", "new options")))

        val result = visibleEntries(listOf(reviews, ideas), "security", emptyMap())

        assertEquals(listOf("Reviews"), result.map(LibraryEntry::displayName))
        val visibleReviews = assertIs<LibraryEntry.Folder>(result.single())
        val visibleSecurity = assertIs<LibraryEntry.Folder>(visibleReviews.children.single())
        assertEquals(listOf("Audit a change"), visibleSecurity.children.map(LibraryEntry::displayName))
    }

    @Test
    fun `search matches indexed Markdown body`() {
        val audit = template("Reviews/audit", "Audit", "ordinary text")

        val result = visibleEntries(
            listOf(folder("Reviews", listOf(audit))),
            "authorization",
            mapOf(audit.directory to "Check authorization boundaries"),
        )

        assertEquals("Audit", (result.single() as LibraryEntry.Folder).children.single().displayName)
    }

    @Test
    fun `search combines folder path and template fields across terms`() {
        val audit = template("Reviews/Security/audit", "Audit permissions", "ordinary text")
        val entries = listOf(folder("Reviews", listOf(folder("Reviews/Security", listOf(audit)))))

        val result = visibleEntries(entries, "reviews permissions", emptyMap())

        val reviews = assertIs<LibraryEntry.Folder>(result.single())
        val security = assertIs<LibraryEntry.Folder>(reviews.children.single())
        assertEquals("Audit permissions", security.children.single().displayName)
    }

    @Test
    fun `search accepts slash-delimited paths independent of the platform separator`() {
        val audit = template("Reviews/Security/audit", "Audit", "")
        val entries = listOf(folder("Reviews", listOf(folder("Reviews/Security", listOf(audit)))))

        val result = visibleEntries(entries, "Reviews/Security", emptyMap())

        assertEquals("Reviews/Security", portablePath(Path.of("Reviews", "Security")))
        assertEquals("Reviews", result.single().displayName)
    }

    @Test
    fun `move down emits after the next sibling of the same kind`() {
        val first = template("a", "A", "")
        val folder = folder("Folder", emptyList())
        val second = template("b", "B", "")
        val snapshot = LibrarySnapshot(root, listOf(folder, first, second))

        val move = siblingMove(snapshot, first.directory, folder = false, direction = 1)

        assertEquals(root, move?.destination)
        assertEquals(EntryPlacement.After(second.directory), move?.placement)
    }

    @Test
    fun `move up in a nested folder emits before the prior sibling`() {
        val first = template("Reviews/a", "A", "")
        val second = template("Reviews/b", "B", "")
        val reviews = folder("Reviews", listOf(first, second))
        val snapshot = LibrarySnapshot(root, listOf(reviews))

        val move = siblingMove(snapshot, second.directory, folder = false, direction = -1)

        assertEquals(reviews.directory, move?.destination)
        assertEquals(EntryPlacement.Before(first.directory), move?.placement)
    }

    @Test
    fun `move ignores the other entry kind and stops at a group boundary`() {
        val folder = folder("Folder", emptyList())
        val template = template("template", "Template", "")
        val snapshot = LibrarySnapshot(root, listOf(folder, template))

        assertNull(siblingMove(snapshot, folder.directory, folder = true, direction = 1))
        assertNull(siblingMove(snapshot, template.directory, folder = false, direction = -1))
    }

    @Test
    fun `expanded paths remap a moved folder and all descendants`() {
        assertEquals(
            listOf("Archive/Reviews", "Archive/Reviews/Security", "Ideas"),
            remapExpandedPaths(
                listOf("Reviews", "Reviews/Security", "Ideas"),
                oldPrefix = "Reviews",
                newPrefix = "Archive/Reviews",
            ),
        )
    }

    @Test
    fun `selection restores the exact path before a duplicated UUID`() {
        val duplicateId = TemplateId.random()
        val first = template("First/a", "First copy", "", duplicateId)
        val second = template("Second/b", "Second copy", "", duplicateId)
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        tree.updateLibrary(
            snapshot = LibrarySnapshot(root, listOf(folder("First", listOf(first)), folder("Second", listOf(second)))),
            bodyIndex = emptyMap(),
            searchQuery = "",
            preferredSelection = LibrarySelectionKey(
                templateId = duplicateId.value,
                relativePath = "Second/b",
            ),
            expandedPaths = emptyList(),
        )

        assertEquals(second.directory, assertIs<LibraryTreeSelection.Template>(tree.selectedSelection()).directory)
    }

    @Test
    fun `selection does not guess when a moved UUID has multiple matches`() {
        val duplicateId = TemplateId.random()
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        tree.updateLibrary(
            snapshot = LibrarySnapshot(
                root,
                listOf(
                    template("a", "First copy", "", duplicateId),
                    template("b", "Second copy", "", duplicateId),
                ),
            ),
            bodyIndex = emptyMap(),
            searchQuery = "",
            preferredSelection = LibrarySelectionKey(
                templateId = duplicateId.value,
                relativePath = "missing",
            ),
            expandedPaths = emptyList(),
        )

        assertNull(tree.selectedSelection())
    }

    @Test
    fun `pending detail request follows a unique move instead of a replacement at the old path`() {
        val stableId = TemplateId.random()
        val moved = template("Archive/original", "Moved", "", stableId)
        val replacement = template("original", "Replacement", "", TemplateId.random())

        val resolved = resolveTemplateEntry(
            TemplateDetailTarget(root.resolve("original"), stableId.value),
            listOf(replacement, moved),
        )

        assertEquals(moved.directory, resolved?.directory)
        assertNull(
            resolveTemplateEntry(
                TemplateDetailTarget(root.resolve("deleted"), TemplateId.random().value),
                listOf(replacement, moved),
            ),
        )
    }

    @Test
    fun `recoverable legacy detail resolves only at its exact path`() {
        val legacyDirectory = root.resolve("legacy")
        val legacy = LibraryEntry.Template(
            summary = TemplateSummary(
                id = null,
                name = "Legacy",
                description = null,
                tags = emptyList(),
                directory = legacyDirectory,
                health = TemplateHealth.RECOVERABLE,
            ),
            relativeDirectory = Path.of("legacy"),
        )

        assertEquals(
            legacyDirectory,
            resolveTemplateEntry(
                TemplateDetailTarget(legacyDirectory, "inferred-runtime-id"),
                listOf(legacy),
            )?.directory,
        )
        assertNull(
            resolveTemplateEntry(
                TemplateDetailTarget(root.resolve("old-legacy-path"), templateId = null),
                listOf(legacy),
            ),
        )
    }

    @Test
    fun `filtered duplicate UUID is not mistaken for a unique match`() {
        val duplicateId = TemplateId.random()
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        tree.updateLibrary(
            snapshot = LibrarySnapshot(
                root,
                listOf(
                    template("original", "Original copy", "", duplicateId),
                    template("duplicate", "Visible duplicate", "", duplicateId),
                ),
            ),
            bodyIndex = emptyMap(),
            searchQuery = "visible",
            preferredSelection = LibrarySelectionKey(
                templateId = duplicateId.value,
                relativePath = "original",
            ),
            expandedPaths = emptyList(),
        )

        assertNull(tree.selectedSelection())
    }

    @Test
    fun `unique UUID follows a move when the old path is reused`() {
        val stableId = TemplateId.random()
        val replacementId = TemplateId.random()
        val moved = template("archive/original", "Moved original", "", stableId)
        val replacement = template("original", "Replacement", "", replacementId)
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        tree.updateLibrary(
            snapshot = LibrarySnapshot(root, listOf(folder("archive", listOf(moved)), replacement)),
            bodyIndex = emptyMap(),
            searchQuery = "",
            preferredSelection = LibrarySelectionKey(
                templateId = stableId.value,
                relativePath = "original",
            ),
            expandedPaths = emptyList(),
        )

        assertEquals(moved.directory, assertIs<LibraryTreeSelection.Template>(tree.selectedSelection()).directory)
    }

    @Test
    fun `relative paths use portable separators`() {
        assertEquals("Reviews/Security", portableRelativePath(root, root.resolve("Reviews/Security")))
    }

    @Test
    fun `move to current parent is a no-op`() {
        val source = root.resolve("Reviews/audit")

        assertEquals(false, shouldMoveToFolder(source, root.resolve("Reviews")))
        assertEquals(true, shouldMoveToFolder(source, root.resolve("Archive")))
    }

    @Test
    fun `root diagnostic remains available when the snapshot has children`() {
        val snapshot = LibrarySnapshot(
            root = root,
            children = listOf(template("audit", "Audit", "")),
            diagnostic = "Order file is invalid.",
        )

        assertEquals("Order file is invalid.", libraryDiagnostic(snapshot))
    }

    @Test
    fun `folder menu includes rename and movement commands`() {
        val commands = FOLDER_COMMANDS.filterNotNull().map(MenuCommand::command)

        assertTrue(LibraryTreeCommand.RENAME_FOLDER in commands)
        assertTrue(LibraryTreeCommand.MOVE_TO_FOLDER in commands)
        assertTrue(LibraryTreeCommand.MOVE_UP in commands)
        assertTrue(LibraryTreeCommand.MOVE_DOWN in commands)
    }

    @Test
    fun `context menus expose the commands for each target kind`() {
        assertEquals(
            listOf(
                LibraryTreeCommand.NEW_TEMPLATE,
                LibraryTreeCommand.NEW_FOLDER,
                LibraryTreeCommand.IMPORT_MARKDOWN,
                LibraryTreeCommand.REFRESH,
                LibraryTreeCommand.REVEAL,
                LibraryTreeCommand.COPY_PATH,
                LibraryTreeCommand.EXPAND_ALL,
                LibraryTreeCommand.COLLAPSE_ALL,
            ),
            ROOT_COMMANDS.filterNotNull().map(MenuCommand::command),
        )
        assertEquals(
            listOf(
                LibraryTreeCommand.NEW_TEMPLATE,
                LibraryTreeCommand.NEW_FOLDER,
                LibraryTreeCommand.IMPORT_MARKDOWN,
                LibraryTreeCommand.RENAME_FOLDER,
                LibraryTreeCommand.MOVE_TO_FOLDER,
                LibraryTreeCommand.MOVE_UP,
                LibraryTreeCommand.MOVE_DOWN,
                LibraryTreeCommand.REVEAL,
                LibraryTreeCommand.COPY_PATH,
                LibraryTreeCommand.EXPAND_BRANCH,
                LibraryTreeCommand.COLLAPSE_BRANCH,
                LibraryTreeCommand.DELETE_FOLDER,
            ),
            FOLDER_COMMANDS.filterNotNull().map(MenuCommand::command),
        )
        assertEquals(
            listOf(
                LibraryTreeCommand.USE_TEMPLATE,
                LibraryTreeCommand.EDIT_TEMPLATE,
                LibraryTreeCommand.MOVE_TO_FOLDER,
                LibraryTreeCommand.MOVE_UP,
                LibraryTreeCommand.MOVE_DOWN,
                LibraryTreeCommand.OPEN_MARKDOWN,
                LibraryTreeCommand.REVEAL,
                LibraryTreeCommand.COPY_PATH,
                LibraryTreeCommand.DELETE_TEMPLATE,
            ),
            TEMPLATE_COMMANDS.filterNotNull().map(MenuCommand::command),
        )
    }

    @Test
    fun `mutation commands disable while read-only commands remain available`() {
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.USE_TEMPLATE, mutationsEnabled = false))
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.RENAME_FOLDER, mutationsEnabled = false))
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.MOVE_TO_FOLDER, mutationsEnabled = false))
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.DELETE_FOLDER, mutationsEnabled = false))
        assertEquals(true, isLibraryCommandEnabled(LibraryTreeCommand.COPY_PATH, mutationsEnabled = false))
        assertEquals(true, isLibraryCommandEnabled(LibraryTreeCommand.EXPAND_BRANCH, mutationsEnabled = false))
    }

    @Test
    fun `tree construction publishes its stable accessible name without an available context`() {
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        assertEquals(LIBRARY_TREE_ACCESSIBLE_NAME, tree.accessibleContext.accessibleName)
        assertEquals(
            LIBRARY_TREE_ACCESSIBLE_NAME,
            tree.getClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY),
        )
    }

    @Test
    fun `look and feel update preserves the library renderer and safe folder text`() {
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        tree.updateLibrary(
            LibrarySnapshot(root, listOf(folder("Reviews", emptyList()))),
            emptyMap(),
            "",
            null,
            emptyList(),
        )
        val expectedRendererClass = tree.cellRenderer.javaClass
        tree.cellRenderer = DefaultTreeCellRenderer()
        val publishedRenderers = mutableListOf<Any?>()
        tree.addPropertyChangeListener("cellRenderer") { event -> publishedRenderers += event.newValue }

        tree.updateUI()

        assertEquals(expectedRendererClass, tree.cellRenderer.javaClass)
        assertTrue(publishedRenderers.any(expectedRendererClass::isInstance))
        assertEquals("Reviews", tree.model.getChild(tree.model.root, 0).toString())
    }

    @Test
    fun `library renderer gives folders and prompts distinct native icons`() {
        val prompt = template("prompt", "Prompt", "")
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        tree.updateLibrary(
            LibrarySnapshot(root, listOf(folder("Reviews", emptyList()), prompt)),
            emptyMap(),
            "",
            null,
            emptyList(),
        )
        val rootNode = tree.model.root
        val renderer = assertIs<ColoredTreeCellRenderer>(tree.cellRenderer)

        renderer.getTreeCellRendererComponent(
            tree,
            tree.model.getChild(rootNode, 0),
            false,
            false,
            true,
            0,
            false,
        )
        assertEquals(AllIcons.Nodes.Folder, renderer.icon)

        renderer.getTreeCellRendererComponent(
            tree,
            tree.model.getChild(rootNode, 1),
            false,
            false,
            true,
            1,
            false,
        )
        assertEquals(AllIcons.FileTypes.Markdown, renderer.icon)
    }

    @Test
    fun `preferred selection restores the author origin after tree navigation`() {
        val first = template("Reviews/a", "A", "")
        val second = template("Reviews/b", "B", "")
        val snapshot = LibrarySnapshot(root, listOf(folder("Reviews", listOf(first, second))))
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        val firstKey = LibrarySelectionKey(templateId = first.summary.id?.value)

        tree.updateLibrary(snapshot, emptyMap(), "", firstKey, emptyList())
        tree.selectTemplateByDirectory(second.directory)
        tree.updateLibrary(snapshot, emptyMap(), "", firstKey, emptyList())

        assertEquals(first.summary.id?.value, tree.currentSelectionKey()?.templateId)
    }

    @Test
    fun `a user-selected search result becomes the selection restored after clearing search`() {
        val first = template("a", "Alpha", "")
        val second = template("b", "Beta", "")
        val snapshot = LibrarySnapshot(root, listOf(first, second))
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        tree.updateLibrary(
            snapshot,
            emptyMap(),
            "",
            LibrarySelectionKey(templateId = first.summary.id?.value),
            emptyList(),
        )
        tree.updateLibrary(snapshot, emptyMap(), "Beta", null, emptyList())
        tree.selectTemplateByDirectory(second.directory)
        tree.updateLibrary(snapshot, emptyMap(), "", null, emptyList())

        assertEquals(second.directory, assertIs<LibraryTreeSelection.Template>(tree.selectedSelection()).directory)
    }

    @Test
    fun `a pending explicit selection overrides the pre-search selection when the filter clears`() {
        val first = template("a", "Alpha", "")
        val second = template("b", "Beta", "")
        val snapshot = LibrarySnapshot(root, listOf(first, second))
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})

        tree.updateLibrary(
            snapshot,
            emptyMap(),
            "",
            LibrarySelectionKey(templateId = first.summary.id?.value),
            emptyList(),
        )
        tree.updateLibrary(snapshot, emptyMap(), "Alpha", null, emptyList())
        tree.updateLibrary(
            snapshot = snapshot,
            bodyIndex = emptyMap(),
            searchQuery = "",
            preferredSelection = LibrarySelectionKey(templateId = second.summary.id?.value),
            expandedPaths = emptyList(),
            preferPreferredSelection = true,
        )

        assertEquals(second.directory, assertIs<LibraryTreeSelection.Template>(tree.selectedSelection()).directory)
    }

    private fun folder(relative: String, children: List<LibraryEntry>): LibraryEntry.Folder = LibraryEntry.Folder(
        directory = root.resolve(relative),
        relativeDirectory = Path.of(relative),
        displayName = Path.of(relative).fileName.toString(),
        children = children,
    )

    private fun template(
        relative: String,
        name: String,
        body: String,
        id: TemplateId = TemplateId(UUID.nameUUIDFromBytes(relative.toByteArray()).toString()),
    ): LibraryEntry.Template {
        val directory = root.resolve(relative)
        return LibraryEntry.Template(
            summary = TemplateSummary(
                id = id,
                name = name,
                description = body,
                tags = emptyList(),
                directory = directory,
                health = TemplateHealth.HEALTHY,
            ),
            relativeDirectory = Path.of(relative),
        )
    }
}
