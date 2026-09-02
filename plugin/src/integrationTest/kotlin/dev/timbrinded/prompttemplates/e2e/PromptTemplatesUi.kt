package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PromptTemplatesUi(
    private val driver: Driver,
) {
    fun open(): PromptTemplatesUi = apply {
        driver.invokeAction(OPEN_ACTION_ID, now = false)
        libraryTree().waitFound(1.minutes)
    }

    fun libraryTree(): JTreeUiComponent = driver.ideFrame().accessibleTree {
        byAccessibleName(LIBRARY_TREE_ACCESSIBLE_NAME)
    }

    fun expandAll(): List<String> = libraryTree()
        .expandAll()
        .collectExpandedPathsAsStrings()

    fun rightClickPath(vararg path: String): List<String> {
        openContextMenu(*path)
        return lightweightContextMenu().getAllTexts().map { it.text }.distinct()
    }

    fun selectContextMenuItem(vararg path: String, item: String) {
        openContextMenu(*path)
        clickContextMenuItem(item)
    }

    fun selectRootContextMenuItem(item: String) {
        val tree = libraryTree()
        val treeBounds = tree.boundsOnScreen
        openContextMenu(
            tree = tree,
            point = Point(
                (treeBounds.width - 2).coerceAtLeast(0),
                (treeBounds.height - 2).coerceAtLeast(0),
            ),
        )
        clickContextMenuItem(item)
    }

    private fun clickContextMenuItem(item: String) {
        val menuItem = lightweightContextMenu().x { byAccessibleName(item) }.waitFound()
        clickComponent(menuItem)
    }

    private fun clickComponent(component: UiComponent) {
        val bounds = component.boundsOnScreen
        clickComponentAt(component, Point(bounds.width / 2, bounds.height / 2))
    }

    private fun clickComponentAt(component: UiComponent, point: Point) {
        val now = System.currentTimeMillis()
        val eventQueue = driver.utility(RemoteToolkit::class)
            .getDefaultToolkit()
            .getSystemEventQueue()
        eventQueue.postEvent(
            driver.new(
                PopupTriggerEvent::class,
                component.component,
                MouseEvent.MOUSE_PRESSED,
                now,
                InputEvent.BUTTON1_DOWN_MASK,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        eventQueue.postEvent(
            driver.new(
                PopupTriggerEvent::class,
                component.component,
                MouseEvent.MOUSE_RELEASED,
                now,
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
    }

    fun createFolder(parentPath: List<String>, name: String) {
        selectContextMenuItem(*parentPath.toTypedArray(), item = "New Folder Here")
        driver.ui.dialog(title = "New Prompt Template Folder") {
            waitFound(30.seconds)
            textField().text = name
            clickComponent(okButton)
        }
        driver.ui.waitForNoOpenedDialogs()
        waitForPath(*(parentPath + name).toTypedArray())
    }

    fun createDefaultTemplate(parentPath: List<String>, name: String) {
        selectContextMenuItem(*parentPath.toTypedArray(), item = "New Template Here")
        val frame = driver.ideFrame()
        val saveButton = frame.button("Save Template").waitFound(30.seconds)
        frame.textField { byVisibleText("New prompt") }.text = name
        clickComponent(saveButton)
        waitForPath(*(parentPath + name).toTypedArray())
    }

    fun selectPath(vararg path: String) {
        val tree = libraryTree().expandAll()
        val expectedPath = path.toList()
        clickComponentAt(tree, tree.fixture.getRowPoint(rowFor(tree, expectedPath)))
        waitFor("tree path $expectedPath is selected", 30.seconds) {
            runCatching {
                libraryTree().collectSelectedPaths().any { selected ->
                    libraryPathEndsWith(selected.path, expectedPath)
                }
            }.getOrDefault(false)
        }
    }

    fun dragPathOnto(sourcePath: List<String>, destinationPath: List<String>) {
        val tree = libraryTree().expandAll()
        val sourceRow = rowFor(tree, sourcePath)
        val destinationRow = rowFor(tree, destinationPath)
        val targetWithinTree = tree.fixture.scrollToRowAndGetVisibleCenter(destinationRow)
        val treeBounds = tree.boundsOnScreen
        tree.dragAndDropRowByNumberToPoint(
            sourceRow,
            Point(
                treeBounds.x + targetWithinTree.x,
                treeBounds.y + targetWithinTree.y,
            ),
        )
    }

    fun waitForPath(vararg path: String) {
        waitFor("tree path ${path.toList()} is visible", 30.seconds) {
            runCatching { hasPath(libraryTree().expandAll(), path.toList()) }.getOrDefault(false)
        }
    }

    fun waitForPathAbsent(vararg path: String) {
        waitFor("tree path ${path.toList()} is absent", 30.seconds) {
            runCatching { !hasPath(libraryTree().expandAll(), path.toList()) }.getOrDefault(false)
        }
    }

    fun waitForVisiblePath(vararg path: String) {
        waitFor("tree path ${path.toList()} is visible", 30.seconds) {
            runCatching { hasPath(libraryTree(), path.toList()) }.getOrDefault(false)
        }
    }

    fun waitForVisiblePathAbsent(vararg path: String) {
        waitFor("tree path ${path.toList()} is not visible", 30.seconds) {
            runCatching { !hasPath(libraryTree(), path.toList()) }.getOrDefault(false)
        }
    }

    fun waitForVisibleText(text: String) {
        driver.ui.x { contains(byVisibleText(text)) }.waitFound(30.seconds)
    }

    fun confirmFolderDeletion(folderPath: List<String>, typedName: String) {
        selectContextMenuItem(*folderPath.toTypedArray(), item = "Delete Folder…")
        driver.ui.dialog(title = "Delete Prompt Template Folder") {
            waitFound(30.seconds)
            textField().text = typedName
            clickComponent(okButton)
        }
        driver.ui.waitForNoOpenedDialogs()
    }

    fun orderedVisiblePaths(): List<String> = libraryTree()
        .expandAll()
        .collectExpandedPaths()
        .sortedBy { it.row }
        .map { it.path.joinToString("/") }

    fun expandedRowCount(): Int = driver.withContext(OnDispatcher.EDT) {
        val tree = cast(libraryTree().component, TreeState::class)
        var count = 0
        for (row in 0 until tree.getRowCount()) {
            if (tree.isExpanded(row)) count++
        }
        count
    }

    private fun openContextMenu(vararg path: String) {
        val tree = libraryTree().expandAll()
        val row = rowFor(tree, path.toList())
        openContextMenu(tree, tree.fixture.getRowPoint(row))
    }

    private fun openContextMenu(tree: JTreeUiComponent, point: Point) {
        driver.withContext(OnDispatcher.EDT) {
            val target = cast(tree.component, PopupEventTarget::class)
            val event = new(
                PopupTriggerEvent::class,
                tree.component,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                InputEvent.BUTTON3_DOWN_MASK,
                point.x,
                point.y,
                1,
                true,
                MouseEvent.BUTTON3,
            )
            target.dispatchEvent(event)
        }
    }

    private fun lightweightContextMenu() = driver.ui.x { byClass("JPopupMenu") }.waitFound()

    fun selectedPaths(): List<String> = libraryTree()
        .collectSelectedPaths()
        .map { it.path.joinToString("/") }

    fun expandedPaths(): List<String> = libraryTree().collectExpandedPathsAsStrings()

    private fun rowFor(tree: JTreeUiComponent, expectedPath: List<String>): Int {
        val matches = tree.collectExpandedPaths().filter { candidate ->
            libraryPathEndsWith(candidate.path, expectedPath)
        }
        check(matches.size == 1) {
            "Expected one row for $expectedPath, found ${matches.map { it.path }} in ${tree.collectExpandedPathsAsStrings()}"
        }
        return matches.single().row
    }

    private fun hasPath(tree: JTreeUiComponent, expectedPath: List<String>): Boolean =
        tree.collectExpandedPaths().any { candidate ->
            libraryPathEndsWith(candidate.path, expectedPath)
        }

    companion object {
        const val LIBRARY_TREE_ACCESSIBLE_NAME = "Prompt template library tree"
        private const val OPEN_ACTION_ID = "PromptTemplates.Open"
    }
}

internal fun libraryPathEndsWith(
    actual: List<String>,
    expected: List<String>,
): Boolean = actual.size >= expected.size &&
    actual.takeLast(expected.size)
        .zip(expected)
        .all { (actualSegment, expectedSegment) ->
            actualSegment.matchesLibrarySegment(expectedSegment)
        }

private fun String.matchesLibrarySegment(expected: String): Boolean {
    if (equals(expected, ignoreCase = true)) return true
    if (!startsWith(expected, ignoreCase = true)) return false
    val presentation = drop(expected.length)
    return presentation.startsWith(", ") || presentation.startsWith("  ")
}

internal fun String.endsWithLibraryPath(expected: String): Boolean =
    libraryPathEndsWith(split('/'), expected.split('/'))

@Remote("java.awt.Component")
private interface PopupEventTarget {
    fun dispatchEvent(event: PopupTriggerEvent)
}

@Remote("java.awt.event.MouseEvent")
private interface PopupTriggerEvent

@Remote("javax.swing.JTree")
private interface TreeState {
    fun getRowCount(): Int
    fun isExpanded(row: Int): Boolean
}

@Remote("java.awt.Toolkit")
private interface RemoteToolkit {
    fun getDefaultToolkit(): RemoteToolkit
    fun getSystemEventQueue(): RemoteEventQueue
}

@Remote("java.awt.EventQueue")
private interface RemoteEventQueue {
    fun postEvent(event: PopupTriggerEvent)
}
