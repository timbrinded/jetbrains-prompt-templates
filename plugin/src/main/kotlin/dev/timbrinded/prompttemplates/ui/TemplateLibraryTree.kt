package dev.timbrinded.prompttemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import dev.timbrinded.prompttemplates.core.EntryPlacement
import dev.timbrinded.prompttemplates.core.LibraryEntry
import dev.timbrinded.prompttemplates.core.LibrarySnapshot
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSearch
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.accessibility.AccessibleContext
import java.nio.file.Path
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.TransferHandler
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal sealed interface LibraryTreeSelection {
    val directory: Path

    data class Root(override val directory: Path) : LibraryTreeSelection {
        override fun toString(): String = directory.fileName?.toString() ?: directory.toString()
    }

    data class Folder(val entry: LibraryEntry.Folder) : LibraryTreeSelection {
        override val directory: Path = entry.directory
        override fun toString(): String = entry.displayName
    }

    data class Template(val entry: LibraryEntry.Template) : LibraryTreeSelection {
        override val directory: Path = entry.summary.directory
        override fun toString(): String = entry.summary.name
    }
}

internal enum class LibraryTreeCommand {
    NEW_TEMPLATE,
    NEW_FOLDER,
    IMPORT_MARKDOWN,
    RENAME_FOLDER,
    REFRESH,
    REVEAL,
    COPY_PATH,
    EXPAND_ALL,
    COLLAPSE_ALL,
    EXPAND_BRANCH,
    COLLAPSE_BRANCH,
    USE_TEMPLATE,
    EDIT_TEMPLATE,
    MOVE_TO_FOLDER,
    MOVE_UP,
    MOVE_DOWN,
    OPEN_MARKDOWN,
    DELETE_FOLDER,
    DELETE_TEMPLATE,
}

internal sealed interface LibrarySelectionKey {
    val relativePath: String?

    data class Folder(override val relativePath: String) : LibrarySelectionKey

    data class Template(
        val templateId: String,
        override val relativePath: String? = null,
    ) : LibrarySelectionKey

    data class TemplatePath(override val relativePath: String) : LibrarySelectionKey
}

/**
 * The library navigation widget. Filesystem work stays in [PromptTemplatesController]; this class owns
 * presentation, selection, filtering, context menus, keyboard movement and Swing drag-and-drop.
 */
internal class TemplateLibraryTree(
    private val onSelection: (LibraryTreeSelection) -> Unit,
    private val onCommand: (LibraryTreeCommand, LibraryTreeSelection) -> Unit,
    private val onMove: (LibraryTreeSelection, Path, EntryPlacement) -> Unit,
    private val onExpansionChanged: (Set<String>) -> Unit,
) : Tree(DefaultTreeModel(DefaultMutableTreeNode())) {
    private var snapshot = LibrarySnapshot(Path.of("."), emptyList())
    private var bodyIndex: Map<Path, String> = emptyMap()
    private var query = ""
    private var rebuilding = false
    private var persistedExpandedPaths = linkedSetOf<String>()
    private var selectionBeforeSearch: LibrarySelectionKey? = null
    private var draggedSelection: LibraryTreeSelection? = null
    private var mutationsEnabled = true
    private var fallbackAccessibleContext: AccessibleContext? = null

    init {
        isRootVisible = false
        setShowsRootHandles(true)
        selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        emptyText.text = "No prompt templates yet"
        putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, LIBRARY_TREE_ACCESSIBLE_NAME)
        setCellRenderer(LibraryTreeRenderer())
        setToggleClickCount(2)

        addTreeSelectionListener {
            if (!rebuilding) selectedSelection()?.let { selection ->
                if (query.isNotBlank()) selectionBeforeSearch = selectionKey(selection, snapshot.root)
                onSelection(selection)
            }
        }
        addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = expansionChanged()
            override fun treeCollapsed(event: TreeExpansionEvent) = expansionChanged()
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = maybeShowPopup(event)
            override fun mouseReleased(event: MouseEvent) = maybeShowPopup(event)
        })
        installKeyboardActions()
        installDragAndDrop()
    }

    override fun getAccessibleContext(): AccessibleContext {
        val context = super.getAccessibleContext()
            ?: fallbackAccessibleContext
            ?: AccessibleJTree().also { fallbackAccessibleContext = it }
        context.accessibleName = LIBRARY_TREE_ACCESSIBLE_NAME
        return context
    }

    override fun updateUI() {
        super.updateUI()
        // IntelliJ can install a default renderer during a late look-and-feel refresh. Restore the
        // semantic renderer after the UI delegate has finished applying its defaults.
        setCellRenderer(LibraryTreeRenderer())
    }

    fun updateLibrary(
        snapshot: LibrarySnapshot,
        bodyIndex: Map<Path, String>,
        searchQuery: String,
        preferredSelection: LibrarySelectionKey?,
        expandedPaths: Collection<String>,
        preferPreferredSelection: Boolean = false,
    ) {
        val wasSearching = query.isNotBlank()
        val willSearch = searchQuery.isNotBlank()
        val previousSelection = selectionKey(selectedSelection(), this.snapshot.root)
        if (!wasSearching && willSearch) {
            persistedExpandedPaths = captureExpandedFolderPaths().toCollection(linkedSetOf())
            selectionBeforeSearch = previousSelection
        }
        if (!wasSearching && !willSearch) persistedExpandedPaths = expandedPaths.toCollection(linkedSetOf())

        this.snapshot = snapshot
        this.bodyIndex = bodyIndex
        query = searchQuery
        emptyText.text = snapshot.diagnostic ?: "No prompt templates yet"
        rebuilding = true
        try {
            val root = DefaultMutableTreeNode(LibraryTreeSelection.Root(snapshot.root))
            visibleEntries(snapshot.children, searchQuery, bodyIndex).forEach { root.add(nodeFor(it)) }
            model = DefaultTreeModel(root)

            if (willSearch) {
                expandEveryRow()
            } else {
                if (wasSearching) persistedExpandedPaths = expandedPaths.toCollection(linkedSetOf())
                restoreExpandedFolderPaths(persistedExpandedPaths)
            }
            val selectionToRestore = if (wasSearching && !willSearch && !preferPreferredSelection) {
                selectionBeforeSearch ?: preferredSelection ?: previousSelection
            } else {
                preferredSelection ?: previousSelection
            }
            restoreSelection(selectionToRestore)
            if (!willSearch) selectionBeforeSearch = null
        } finally {
            rebuilding = false
        }
        selectedSelection()?.let(onSelection)
    }

    fun selectedSelection(): LibraryTreeSelection? =
        (lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? LibraryTreeSelection

    fun selectedDestinationFolder(): Path = when (val selected = selectedSelection()) {
        is LibraryTreeSelection.Folder -> selected.directory
        is LibraryTreeSelection.Template -> selected.directory.parent
        is LibraryTreeSelection.Root, null -> snapshot.root
    }

    fun setMutationsEnabled(enabled: Boolean) {
        mutationsEnabled = enabled
        if (!enabled) draggedSelection = null
        if (!GraphicsEnvironment.isHeadless()) dragEnabled = enabled
    }

    fun currentSelectionKey(): LibrarySelectionKey? = selectionKey(selectedSelection(), snapshot.root)

    fun captureExpandedFolderPaths(): Set<String> {
        val paths = linkedSetOf<String>()
        for (row in 0 until rowCount) {
            if (!isExpanded(row)) continue
            val selection = nodeSelection(getPathForRow(row)) as? LibraryTreeSelection.Folder ?: continue
            paths += portableRelativePath(snapshot.root, selection.directory)
        }
        return paths
    }

    fun expandAll() = expandEveryRow()

    fun collapseAll() {
        for (row in rowCount - 1 downTo 0) collapseRow(row)
    }

    fun expandSelectedBranch() {
        val base = selectionPath ?: return
        expandDescendants(base)
    }

    fun collapseSelectedBranch() {
        selectionPath?.let(::collapsePath)
    }

    fun selectTemplateByDirectory(directory: Path) {
        findPath { selection -> selection is LibraryTreeSelection.Template && selection.directory == directory }
            ?.let {
                selectionPath = it
                if (isShowing) scrollPathToVisible(it)
            }
    }

    private fun nodeFor(entry: LibraryEntry): DefaultMutableTreeNode = when (entry) {
        is LibraryEntry.Folder -> DefaultMutableTreeNode(LibraryTreeSelection.Folder(entry)).also { node ->
            entry.children.forEach { node.add(nodeFor(it)) }
        }
        is LibraryEntry.Template -> DefaultMutableTreeNode(LibraryTreeSelection.Template(entry))
    }

    private fun restoreSelection(key: LibrarySelectionKey?) {
        if (key == null) return
        val path = when (key) {
            is LibrarySelectionKey.Folder -> findPath { selection ->
                selection is LibraryTreeSelection.Folder &&
                    portableRelativePath(snapshot.root, selection.directory) == key.relativePath
            }
            is LibrarySelectionKey.TemplatePath -> findPath { selection ->
                selection is LibraryTreeSelection.Template &&
                    portableRelativePath(snapshot.root, selection.directory) == key.relativePath
            }
            is LibrarySelectionKey.Template -> {
                val exactPath = key.relativePath?.let { relativePath ->
                    findPath { selection ->
                        selection is LibraryTreeSelection.Template &&
                            portableRelativePath(snapshot.root, selection.directory) == relativePath &&
                            selection.entry.summary.id?.value.equals(key.templateId, ignoreCase = true)
                    }
                }
                val matchingIds = flattenTemplates(snapshot.children).count { entry ->
                    entry.summary.id?.value.equals(key.templateId, ignoreCase = true)
                }
                exactPath ?: if (matchingIds == 1) {
                    findPath { selection ->
                        selection is LibraryTreeSelection.Template &&
                            selection.entry.summary.id?.value.equals(key.templateId, ignoreCase = true)
                    }
                } else {
                    null
                }
            }
        }
        path ?: return
        selectionPath = path
        if (isShowing) scrollPathToVisible(path)
    }

    private fun findPath(predicate: (LibraryTreeSelection) -> Boolean): TreePath? {
        return findPaths(predicate).firstOrNull()
    }

    private fun findPaths(predicate: (LibraryTreeSelection) -> Boolean): List<TreePath> {
        val root = model.root as? DefaultMutableTreeNode ?: return emptyList()
        return root.depthFirstEnumeration().asSequence()
            .mapNotNull { it as? DefaultMutableTreeNode }
            .filter { node -> (node.userObject as? LibraryTreeSelection)?.let(predicate) == true }
            .map { TreePath(it.path) }
            .toList()
    }

    private fun restoreExpandedFolderPaths(paths: Collection<String>) {
        paths.forEach { relative ->
            findPath { selection ->
                selection is LibraryTreeSelection.Folder &&
                    portableRelativePath(snapshot.root, selection.directory) == relative
            }?.let(::expandPath)
        }
    }

    private fun expansionChanged() {
        if (rebuilding || query.isNotBlank()) return
        persistedExpandedPaths = captureExpandedFolderPaths().toCollection(linkedSetOf())
        onExpansionChanged(persistedExpandedPaths)
    }

    private fun expandEveryRow() {
        var row = 0
        while (row < rowCount) {
            expandRow(row)
            row++
        }
    }

    private fun expandDescendants(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        node.depthFirstEnumeration().asSequence()
            .mapNotNull { it as? DefaultMutableTreeNode }
            .map { TreePath(it.path) }
            .forEach(::expandPath)
    }

    private fun maybeShowPopup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val path = getPathForLocation(event.x, event.y)
        val target = if (path == null) {
            clearSelection()
            LibraryTreeSelection.Root(snapshot.root)
        } else {
            selectionPath = path
            nodeSelection(path) ?: return
        }
        popupFor(target).show(this, event.x, event.y)
    }

    private fun popupFor(target: LibraryTreeSelection): JPopupMenu = JPopupMenu().apply {
        val commands = when (target) {
            is LibraryTreeSelection.Root -> ROOT_COMMANDS
            is LibraryTreeSelection.Folder -> FOLDER_COMMANDS
            is LibraryTreeSelection.Template -> TEMPLATE_COMMANDS
        }
        commands.forEach { command ->
            if (command == null) addSeparator() else add(JMenuItem(command.label).apply {
                isEnabled = isLibraryCommandEnabled(command.command, mutationsEnabled)
                addActionListener { runCommand(command.command, target) }
            })
        }
    }

    private fun runCommand(command: LibraryTreeCommand, target: LibraryTreeSelection) {
        if (!mutationsEnabled && command in MUTATION_COMMANDS) return
        when (command) {
            LibraryTreeCommand.EXPAND_ALL -> expandAll()
            LibraryTreeCommand.COLLAPSE_ALL -> collapseAll()
            LibraryTreeCommand.EXPAND_BRANCH -> expandSelectedBranch()
            LibraryTreeCommand.COLLAPSE_BRANCH -> collapseSelectedBranch()
            else -> onCommand(command, target)
        }
    }

    private fun installKeyboardActions() {
        registerKeyboardAction(
            { selectedSelection()?.let { onCommand(LibraryTreeCommand.MOVE_UP, it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
        registerKeyboardAction(
            { selectedSelection()?.let { onCommand(LibraryTreeCommand.MOVE_DOWN, it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
        registerKeyboardAction(
            { selectedSelection()?.let { onCommand(LibraryTreeCommand.MOVE_TO_FOLDER, it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
        registerKeyboardAction(
            {
                val target = selectedSelection() ?: LibraryTreeSelection.Root(snapshot.root)
                val y = selectionRows?.firstOrNull()?.let(::getRowBounds)?.y ?: 0
                popupFor(target).show(this, 0, y)
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
    }

    private fun installDragAndDrop() {
        if (GraphicsEnvironment.isHeadless()) return
        dragEnabled = true
        dropMode = DropMode.ON_OR_INSERT
        transferHandler = object : TransferHandler() {
            override fun getSourceActions(component: JComponent): Int = MOVE

            override fun createTransferable(component: JComponent): java.awt.datatransfer.Transferable? {
                draggedSelection = selectedSelection()?.takeUnless { it is LibraryTreeSelection.Root }
                return draggedSelection?.let { StringSelection(it.directory.toString()) }
            }

            override fun canImport(support: TransferSupport): Boolean =
                mutationsEnabled && support.isDrop && draggedSelection != null && resolveDrop(support) != null

            override fun importData(support: TransferSupport): Boolean {
                val source = draggedSelection ?: return false
                val target = resolveDrop(support) ?: return false
                onMove(source, target.first, target.second)
                return true
            }

            override fun exportDone(source: JComponent, data: java.awt.datatransfer.Transferable?, action: Int) {
                draggedSelection = null
            }
        }
    }

    private fun resolveDrop(support: TransferHandler.TransferSupport): Pair<Path, EntryPlacement>? {
        val drop = support.dropLocation as? JTree.DropLocation ?: return null
        val source = draggedSelection ?: return null
        val path = drop.path ?: return snapshot.root to EntryPlacement.EndOfKind
        val target = nodeSelection(path) ?: return null
        if (drop.childIndex < 0) {
            val destination = when (target) {
                is LibraryTreeSelection.Root -> target.directory
                is LibraryTreeSelection.Folder -> target.directory
                is LibraryTreeSelection.Template -> return null
            }
            return destination to EntryPlacement.EndOfKind
        }

        val parentNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val destination = when (target) {
            is LibraryTreeSelection.Root -> target.directory
            is LibraryTreeSelection.Folder -> target.directory
            is LibraryTreeSelection.Template -> return null
        }
        val siblingNode = if (drop.childIndex < parentNode.childCount) {
            parentNode.getChildAt(drop.childIndex) as? DefaultMutableTreeNode
        } else {
            null
        }
        val sibling = siblingNode?.userObject as? LibraryTreeSelection
        if (sibling == null) return destination to EntryPlacement.EndOfKind
        val sameKind = (source is LibraryTreeSelection.Folder && sibling is LibraryTreeSelection.Folder) ||
            (source is LibraryTreeSelection.Template && sibling is LibraryTreeSelection.Template)
        if (!sameKind) return null
        // Swing reports an insertion gap by its next child. Before(next), or EndOfKind for the
        // final gap, represents both visual insertion sides without ambiguous source indices.
        return destination to EntryPlacement.Before(sibling.directory)
    }

    private fun nodeSelection(path: TreePath?): LibraryTreeSelection? =
        (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? LibraryTreeSelection
}

private class LibraryTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val item = (value as? DefaultMutableTreeNode)?.userObject as? LibraryTreeSelection) {
            is LibraryTreeSelection.Root -> Unit
            is LibraryTreeSelection.Folder -> {
                icon = AllIcons.Nodes.Folder
                append(item.entry.displayName)
                item.entry.diagnostic?.let { append("  Warning", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                toolTipText = item.entry.diagnostic ?: item.directory.toString()
            }
            is LibraryTreeSelection.Template -> {
                icon = AllIcons.FileTypes.Markdown
                val summary = item.entry.summary
                append(summary.name)
                when (summary.health) {
                    TemplateHealth.BROKEN -> append("  Broken", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    TemplateHealth.RECOVERABLE -> append("  Metadata missing", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    TemplateHealth.HEALTHY -> if (summary.tags.isNotEmpty()) {
                        append("  ${summary.tags.joinToString(", ")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                toolTipText = summary.diagnostic ?: summary.description ?: item.directory.toString()
            }
            null -> Unit
        }
    }
}

internal data class MenuCommand(val command: LibraryTreeCommand, val label: String)

internal val ROOT_COMMANDS: List<MenuCommand?> = listOf(
    MenuCommand(LibraryTreeCommand.NEW_TEMPLATE, "New Template"),
    MenuCommand(LibraryTreeCommand.NEW_FOLDER, "New Folder"),
    null,
    MenuCommand(LibraryTreeCommand.EXPAND_ALL, "Expand All"),
    MenuCommand(LibraryTreeCommand.COLLAPSE_ALL, "Collapse All"),
)

internal val FOLDER_COMMANDS: List<MenuCommand?> = listOf(
    MenuCommand(LibraryTreeCommand.NEW_TEMPLATE, "New Template"),
    MenuCommand(LibraryTreeCommand.NEW_FOLDER, "New Folder"),
    null,
    MenuCommand(LibraryTreeCommand.RENAME_FOLDER, "Rename…"),
    MenuCommand(LibraryTreeCommand.MOVE_TO_FOLDER, "Move to Folder…"),
    null,
    MenuCommand(LibraryTreeCommand.DELETE_FOLDER, "Delete Folder…"),
)

internal val TEMPLATE_COMMANDS: List<MenuCommand?> = listOf(
    MenuCommand(LibraryTreeCommand.EDIT_TEMPLATE, "Edit"),
    MenuCommand(LibraryTreeCommand.OPEN_MARKDOWN, "Open Markdown"),
    null,
    MenuCommand(LibraryTreeCommand.MOVE_TO_FOLDER, "Move to Folder…"),
    null,
    MenuCommand(LibraryTreeCommand.DELETE_TEMPLATE, "Delete Template"),
)

private val MUTATION_COMMANDS = setOf(
    // Opening another template also replaces an open author draft. Keep it disabled with the
    // filesystem mutations until the draft is saved or cancelled.
    LibraryTreeCommand.USE_TEMPLATE,
    LibraryTreeCommand.NEW_TEMPLATE,
    LibraryTreeCommand.NEW_FOLDER,
    LibraryTreeCommand.IMPORT_MARKDOWN,
    LibraryTreeCommand.RENAME_FOLDER,
    LibraryTreeCommand.EDIT_TEMPLATE,
    LibraryTreeCommand.MOVE_TO_FOLDER,
    LibraryTreeCommand.MOVE_UP,
    LibraryTreeCommand.MOVE_DOWN,
    LibraryTreeCommand.DELETE_FOLDER,
    LibraryTreeCommand.DELETE_TEMPLATE,
)

internal fun isLibraryCommandEnabled(command: LibraryTreeCommand, mutationsEnabled: Boolean): Boolean =
    mutationsEnabled || command !in MUTATION_COMMANDS

internal fun portableRelativePath(root: Path, directory: Path): String =
    portablePath(root.toAbsolutePath().normalize().relativize(directory.toAbsolutePath().normalize()))

internal fun portablePath(path: Path): String = path.joinToString("/") { it.toString() }

internal fun selectionKey(selection: LibraryTreeSelection?, root: Path): LibrarySelectionKey? = when (selection) {
    is LibraryTreeSelection.Template -> selection.entry.summary.id?.value?.let { templateId ->
        LibrarySelectionKey.Template(templateId, portableRelativePath(root, selection.directory))
    } ?: LibrarySelectionKey.TemplatePath(portableRelativePath(root, selection.directory))
    is LibraryTreeSelection.Folder -> LibrarySelectionKey.Folder(portableRelativePath(root, selection.directory))
    is LibraryTreeSelection.Root, null -> null
}

internal fun visibleEntries(
    entries: List<LibraryEntry>,
    query: String,
    bodyIndex: Map<Path, String>,
): List<LibraryEntry> {
    if (query.isBlank()) return entries
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    fun filter(entry: LibraryEntry, ancestorMatched: Boolean): LibraryEntry? = when (entry) {
        is LibraryEntry.Template -> {
            val path = portablePath(entry.relativeDirectory)
            val matches = terms.all { term ->
                path.contains(term, ignoreCase = true) ||
                    TemplateSearch.matches(entry.summary, term, bodyIndex[entry.summary.directory])
            }
            entry.takeIf {
                ancestorMatched || matches
            }
        }
        is LibraryEntry.Folder -> {
            val path = portablePath(entry.relativeDirectory)
            val folderMatches = terms.all { term ->
                entry.displayName.contains(term, ignoreCase = true) || path.contains(term, ignoreCase = true)
            }
            val children = entry.children.mapNotNull { filter(it, ancestorMatched || folderMatches) }
            entry.copy(children = children).takeIf { ancestorMatched || folderMatches || children.isNotEmpty() }
        }
    }

    return entries.mapNotNull { filter(it, false) }
}

internal const val LIBRARY_TREE_ACCESSIBLE_NAME = "Prompt template library tree"
