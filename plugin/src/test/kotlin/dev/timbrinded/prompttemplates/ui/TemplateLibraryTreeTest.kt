package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateId
import dev.timbrinded.prompttemplates.core.TemplateSummary
import java.nio.file.Path
import java.util.UUID
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateLibraryTreeTest {
    private val root = Path.of("/library")

    @Test
    fun `search matches names paths bodies and separators`() {
        val audit = template("Reviews/Security/audit", "Audit permissions", "ordinary text")
        val entries = listOf(folder("Reviews", listOf(folder("Reviews/Security", listOf(audit)))))

        // Folder-path match retains ancestors.
        val byPath = visibleEntries(entries, "security", emptyMap())
        assertEquals(listOf("Reviews"), byPath.map(LibraryEntry::displayName))

        // Markdown body match via index.
        val byBody = visibleEntries(entries, "authorization", mapOf(audit.directory to "Check authorization boundaries"))
        assertEquals("Audit permissions", assertIs<LibraryEntry.Folder>(byBody.single()).children.single().let {
            assertIs<LibraryEntry.Folder>(it).children.single().displayName
        })

        // Multi-term match across folder path + template fields.
        val multi = visibleEntries(entries, "reviews permissions", emptyMap())
        assertEquals("Reviews", multi.single().displayName)

        // Slash-delimited query works independent of platform separator.
        val bySlash = visibleEntries(entries, "Reviews/Security", emptyMap())
        assertEquals("Reviews", bySlash.single().displayName)
    }

    @Test
    fun `sibling moves respect kind order and boundaries`() {
        val first = template("a", "A", "")
        val folder = folder("Folder", emptyList())
        val second = template("b", "B", "")
        val snapshot = LibrarySnapshot(root, listOf(folder, first, second))

        // Down: after next sibling of same kind.
        val down = siblingMove(snapshot, LibraryTreeSelection.Template(first), MoveDirection.DOWN)
        assertEquals(root, down?.destination)
        assertEquals(EntryPlacement.After(second.directory), down?.placement)

        // Up in nested folder: before prior sibling.
        val nestedFirst = template("Reviews/a", "A", "")
        val nestedSecond = template("Reviews/b", "B", "")
        val reviews = folder("Reviews", listOf(nestedFirst, nestedSecond))
        val nestedSnapshot = LibrarySnapshot(root, listOf(reviews))
        val up = siblingMove(nestedSnapshot, LibraryTreeSelection.Template(nestedSecond), MoveDirection.UP)
        assertEquals(reviews.directory, up?.destination)
        assertEquals(EntryPlacement.Before(nestedFirst.directory), up?.placement)

        // Cross-kind moves are rejected (group boundary).
        assertNull(siblingMove(snapshot, LibraryTreeSelection.Folder(folder), MoveDirection.DOWN))
        assertNull(siblingMove(snapshot, LibraryTreeSelection.Template(first), MoveDirection.UP))
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
    fun `duplicate UUID selection prefers exact path and refuses to guess`() {
        val duplicateId = TemplateId.random()
        val first = template("First/a", "First copy", "", duplicateId)
        val second = template("Second/b", "Second copy", "", duplicateId)

        // Exact-path preference wins when both copies exist.
        val exactTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        exactTree.updateLibrary(
            snapshot = LibrarySnapshot(root, listOf(folder("First", listOf(first)), folder("Second", listOf(second)))),
            bodyIndex = emptyMap(),
            searchQuery = "",
            selectedKey = LibrarySelectionKey.Template(duplicateId.value, "Second/b"),
            expandedPaths = emptyList(),
        )
        assertEquals(second.directory, assertIs<LibraryTreeSelection.Template>(exactTree.selectedSelection()).directory)

        // Ambiguous move target without an exact path resolves to nothing.
        val ambiguousTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        ambiguousTree.updateLibrary(
            snapshot = LibrarySnapshot(
                root,
                listOf(
                    template("a", "First copy", "", duplicateId),
                    template("b", "Second copy", "", duplicateId),
                ),
            ),
            bodyIndex = emptyMap(),
            searchQuery = "",
            selectedKey = LibrarySelectionKey.Template(duplicateId.value, "missing"),
            expandedPaths = emptyList(),
        )
        assertNull(ambiguousTree.selectedSelection())

        // Filtered view must not mistake a visible duplicate for a unique match.
        val filteredTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        filteredTree.updateLibrary(
            snapshot = LibrarySnapshot(
                root,
                listOf(
                    template("original", "Original copy", "", duplicateId),
                    template("duplicate", "Visible duplicate", "", duplicateId),
                ),
            ),
            bodyIndex = emptyMap(),
            searchQuery = "visible",
            selectedKey = LibrarySelectionKey.Template(duplicateId.value, "original"),
            expandedPaths = emptyList(),
        )
        assertNull(filteredTree.selectedSelection())

        // Unique move is followed even when the old path is reused by a replacement.
        val stableId = TemplateId.random()
        val moved = template("archive/original", "Moved original", "", stableId)
        val replacement = template("original", "Replacement", "", TemplateId.random())
        val movedTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        movedTree.updateLibrary(
            snapshot = LibrarySnapshot(root, listOf(folder("archive", listOf(moved)), replacement)),
            bodyIndex = emptyMap(),
            searchQuery = "",
            selectedKey = LibrarySelectionKey.Template(stableId.value, "original"),
            expandedPaths = emptyList(),
        )
        assertEquals(moved.directory, assertIs<LibraryTreeSelection.Template>(movedTree.selectedSelection()).directory)
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
    fun `context menus expose commands and respect read-only mode`() {
        assertEquals(
            listOf(
                LibraryTreeCommand.NEW_TEMPLATE,
                LibraryTreeCommand.NEW_FOLDER,
                null,
                LibraryTreeCommand.EXPAND_ALL,
                LibraryTreeCommand.COLLAPSE_ALL,
            ),
            ROOT_COMMANDS.map { command -> command?.command },
        )
        assertEquals(
            listOf(
                LibraryTreeCommand.NEW_TEMPLATE,
                LibraryTreeCommand.NEW_FOLDER,
                null,
                LibraryTreeCommand.RENAME_FOLDER,
                LibraryTreeCommand.MOVE_TO_FOLDER,
                null,
                LibraryTreeCommand.DELETE_FOLDER,
            ),
            FOLDER_COMMANDS.map { command -> command?.command },
        )
        assertEquals(
            listOf(
                LibraryTreeCommand.EDIT_TEMPLATE,
                LibraryTreeCommand.DUPLICATE_TEMPLATE,
                LibraryTreeCommand.OPEN_MARKDOWN,
                null,
                LibraryTreeCommand.MOVE_TO_FOLDER,
                null,
                LibraryTreeCommand.DELETE_TEMPLATE,
            ),
            TEMPLATE_COMMANDS.map { command -> command?.command },
        )
        // Read-only: mutations off, navigation/open still on.
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.RENAME_FOLDER, mutationsEnabled = false))
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.MOVE_TO_FOLDER, mutationsEnabled = false))
        assertEquals(false, isLibraryCommandEnabled(LibraryTreeCommand.DELETE_FOLDER, mutationsEnabled = false))
        assertEquals(true, isLibraryCommandEnabled(LibraryTreeCommand.OPEN_MARKDOWN, mutationsEnabled = false))
        assertEquals(true, isLibraryCommandEnabled(LibraryTreeCommand.EXPAND_ALL, mutationsEnabled = false))
    }

    @Test
    fun `controlled selection restores across navigation search and explicit replacement`() {
        // The supplied controller selection replaces temporary tree navigation.
        val first = template("Reviews/a", "A", "")
        val second = template("Reviews/b", "B", "")
        val snapshot = LibrarySnapshot(root, listOf(folder("Reviews", listOf(first, second))))
        val selections = mutableListOf<Path>()
        val tree = TemplateLibraryTree({ selection -> selections.add(selection.directory) }, { _, _ -> }, { _, _, _ -> }, {})
        val firstKey = LibrarySelectionKey.Template(requireNotNull(first.summary.id).value)
        tree.updateLibrary(snapshot, emptyMap(), "", firstKey, emptyList())
        assertTrue(selections.isEmpty())
        tree.selectTemplateByDirectory(second.directory)
        assertEquals(listOf(second.directory), selections)
        tree.updateLibrary(snapshot, emptyMap(), "", firstKey, emptyList())
        assertEquals(listOf(second.directory), selections)
        assertEquals(
            first.summary.id?.value,
            (selectionKey(tree.selectedSelection(), root) as? LibrarySelectionKey.Template)?.templateId,
        )

        // User-picked search result survives clearing the filter.
        val alpha = template("a", "Alpha", "")
        val beta = template("b", "Beta", "")
        val flat = LibrarySnapshot(root, listOf(alpha, beta))
        var controlledKey: LibrarySelectionKey? = LibrarySelectionKey.Template(requireNotNull(alpha.summary.id).value)
        val searchTree = TemplateLibraryTree(
            { selection -> controlledKey = selectionKey(selection, root) },
            { _, _ -> },
            { _, _, _ -> },
            {},
        )
        searchTree.updateLibrary(flat, emptyMap(), "", controlledKey, emptyList())
        searchTree.updateLibrary(flat, emptyMap(), "Beta", controlledKey, emptyList())
        searchTree.selectTemplateByDirectory(beta.directory)
        searchTree.updateLibrary(flat, emptyMap(), "", controlledKey, emptyList())
        assertEquals(beta.directory, assertIs<LibraryTreeSelection.Template>(searchTree.selectedSelection()).directory)

        // Pending explicit selection wins when the filter clears.
        val overrideTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        overrideTree.updateLibrary(flat, emptyMap(), "", LibrarySelectionKey.Template(requireNotNull(alpha.summary.id).value), emptyList())
        overrideTree.updateLibrary(
            flat,
            emptyMap(),
            "Alpha",
            LibrarySelectionKey.Template(requireNotNull(alpha.summary.id).value),
            emptyList(),
        )
        overrideTree.updateLibrary(
            snapshot = flat,
            bodyIndex = emptyMap(),
            searchQuery = "",
            selectedKey = LibrarySelectionKey.Template(requireNotNull(beta.summary.id).value),
            expandedPaths = emptyList(),
        )
        assertEquals(beta.directory, assertIs<LibraryTreeSelection.Template>(overrideTree.selectedSelection()).directory)
    }

    @Test
    fun `persisted expansion restores prunes and publishes`() {
        // Exact restore publishes nothing; expand/collapse publish deltas.
        val recorded = mutableListOf<Set<String>>()
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, { recorded += it })
        val acme = folder("work/clients/acme", listOf(template("work/clients/acme/t", "T", "")))
        val snapshot = LibrarySnapshot(root, listOf(folder("work", listOf(folder("work/clients", listOf(acme))))))
        tree.updateLibrary(snapshot, emptyMap(), "", selectedKey = null, expandedPaths = listOf("work", "work/clients"))
        assertTrue(tree.isExpanded(tree.pathOfFolder("work")))
        assertTrue(tree.isExpanded(tree.pathOfFolder("work/clients")))
        assertFalse(tree.isExpanded(tree.pathOfFolder("work/clients/acme")))
        assertTrue(recorded.isEmpty(), "An exact restore publishes nothing, but recorded $recorded")
        tree.expandPath(tree.pathOfFolder("work/clients/acme"))
        assertEquals(setOf("work", "work/clients", "work/clients/acme"), recorded.last())
        tree.collapsePath(tree.pathOfFolder("work/clients/acme"))
        assertEquals(setOf("work", "work/clients"), recorded.last())

        // Collapsed ancestor is not reopened; persisted child is pruned.
        val prunedRecorded = mutableListOf<Set<String>>()
        val prunedTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, { prunedRecorded += it })
        val clients = folder("work/clients", listOf(template("work/clients/t", "T", "")))
        prunedTree.updateLibrary(
            LibrarySnapshot(root, listOf(folder("work", listOf(clients)))),
            emptyMap(), "", selectedKey = null, expandedPaths = listOf("work/clients"),
        )
        assertFalse(prunedTree.isExpanded(prunedTree.pathOfFolder("work")))
        assertEquals(emptySet(), prunedRecorded.last())

        // Empty snapshot (pre-scan placeholder) neither prunes nor publishes.
        val emptyRecorded = mutableListOf<Set<String>>()
        val emptyTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, { emptyRecorded += it })
        emptyTree.updateLibrary(LibrarySnapshot(root, emptyList()), emptyMap(), "", selectedKey = null, expandedPaths = listOf("work"))
        assertTrue(emptyRecorded.isEmpty(), "The pre-scan placeholder must not publish, but recorded $emptyRecorded")
        assertEquals(setOf("work"), emptyTree.captureExpandedFolderPaths())

        // Selecting inside a collapsed folder publishes expanded ancestors.
        val selectRecorded = mutableListOf<Set<String>>()
        val selectTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, { selectRecorded += it })
        selectTree.updateLibrary(
            LibrarySnapshot(root, listOf(folder("A", listOf(folder("A/B", emptyList()))))),
            emptyMap(), "", selectedKey = LibrarySelectionKey.Folder("A/B"), expandedPaths = emptyList(),
        )
        assertTrue(selectTree.isExpanded(selectTree.pathOfFolder("A")))
        assertEquals(setOf("A"), selectRecorded.last())

        // Stale paths for vanished folders are dropped.
        val staleRecorded = mutableListOf<Set<String>>()
        val staleTree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, { staleRecorded += it })
        staleTree.updateLibrary(
            LibrarySnapshot(root, listOf(folder("Keep", listOf(template("Keep/t", "T", ""))))),
            emptyMap(), "", selectedKey = null, expandedPaths = listOf("Keep", "Gone", "Gone/Deeper"),
        )
        assertEquals(setOf("Keep"), staleRecorded.last())
    }

    @Test
    fun `a folder selected while searching resolves with its unfiltered children`() {
        val tree = TemplateLibraryTree({}, { _, _ -> }, { _, _, _ -> }, {})
        val reviews = folder("Reviews", listOf(template("Reviews/a", "Alpha", ""), template("Reviews/b", "Beta", "")))

        tree.updateLibrary(
            LibrarySnapshot(root, listOf(reviews)),
            emptyMap(),
            "alpha",
            selectedKey = LibrarySelectionKey.Folder("Reviews"),
            expandedPaths = emptyList(),
        )

        assertEquals(2, assertIs<LibraryTreeSelection.Folder>(tree.selectedSelection()).entry.children.size)
    }

    @Test
    fun `an insertion gap before the other kind maps to the end of the source kind`() {
        val folderA = LibraryTreeSelection.Folder(folder("A", emptyList()))
        val folderB = LibraryTreeSelection.Folder(folder("B", emptyList()))
        val first = LibraryTreeSelection.Template(template("t1", "T1", ""))
        val second = LibraryTreeSelection.Template(template("t2", "T2", ""))
        val siblings = listOf(folderA, folderB, first, second)

        assertEquals(EntryPlacement.Before(folderB.directory), insertionGapPlacement(folderA, folderB, siblings))
        assertEquals(EntryPlacement.EndOfKind, insertionGapPlacement(folderA, first, siblings))
        assertEquals(EntryPlacement.Before(second.directory), insertionGapPlacement(first, second, siblings))
        assertEquals(EntryPlacement.Before(first.directory), insertionGapPlacement(second, folderA, siblings))
        assertEquals(EntryPlacement.EndOfKind, insertionGapPlacement(first, folderA, listOf(folderA, folderB)))
        assertEquals(EntryPlacement.EndOfKind, insertionGapPlacement(first, null, siblings))
    }

    private fun TemplateLibraryTree.pathOfFolder(relative: String): TreePath {
        val rootNode = model.root as DefaultMutableTreeNode
        val node = rootNode.depthFirstEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .first { candidate ->
                (candidate.userObject as? LibraryTreeSelection.Folder)
                    ?.let { portablePath(it.entry.relativeDirectory) } == relative
            }
        return TreePath(node.path)
    }

    private fun folder(relative: String, children: List<LibraryEntry>): LibraryEntry.Folder = LibraryEntry.Folder(
        directory = root.resolve(relative),
        relativeDirectory = Path.of(relative),
        displayName = Path.of(relative).name,
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
