package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.getToolWindow
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PromptTemplatesUi(
    private val driver: Driver,
) {
    fun dismissTrialNotification() {
        // The fresh IDE's trial balloon consumes Escape before modeless dialogs see it.
        val close = driver.ideFrame().button { and(byClass("ActionLink"), byAccessibleName("Close")) }.waitFound(30.seconds)
        driver.ideFrame().keyboard { escape() }
        close.waitNotFound(30.seconds)
    }

    fun open(): PromptTemplatesUi = apply {
        driver.invokeAction(OPEN_ACTION_ID, component = driver.ideFrame().component)
        libraryTree().waitFound(1.minutes)
        driver.withContext(OnDispatcher.EDT) {
            val extraHeight = 650 - libraryTree().boundsOnScreen.height
            if (extraHeight > 0) {
                cast(getToolWindow("Prompt Templates"), TestToolWindow::class).stretchHeight(extraHeight)
            }
        }
    }

    fun libraryTree(): JTreeUiComponent = driver.ideFrame().accessibleTree {
        byAccessibleName(LIBRARY_TREE_ACCESSIBLE_NAME)
    }

    fun selectTemplate(vararg path: String) {
        waitForPath(*path)
        val tree = libraryTree().expandAll()
        clickComponentAt(tree, tree.fixture.getRowPoint(rowFor(tree, path.toList())))
        driver.ideFrame().button("Copy Prompt").waitFound(30.seconds)
    }

    fun clickButton(label: String) {
        clickComponent(driver.ideFrame().button(label).waitFound(30.seconds))
    }

    fun clickFileAction(label: String) {
        clickButton("File ▾")
        clickContextMenuItem(label)
    }

    fun clickAuthorAction(label: String) {
        clickButton("Template Markdown ▾")
        clickContextMenuItem(label)
    }

    fun newTemplate() {
        clickButton("New")
        clickContextMenuItem("New Template")
    }

    fun changeLibrary(root: Path) {
        driver.invokeAction("ShowSettings", component = driver.ideFrame().component)
        driver.ideFrame().settingsDialog {
            waitFound(30.seconds)
            searchTextField.text = "Prompt Templates"
            val tree = settingsTree
            waitFor("Prompt Templates settings are available", 30.seconds) {
                runCatching { hasPath(tree.expandAll(), listOf("Prompt Templates")) }.getOrDefault(false)
            }
            clickComponentAt(tree, tree.fixture.getRowPoint(rowFor(tree, listOf("Prompt Templates"))))
            textField { byAccessibleName("Personal library directory") }.waitFound(30.seconds).text = root.toString()
            clickComponent(okButton)
        }
        driver.ui.waitForNoOpenedDialogs()
    }

    fun renderedText(): String {
        val field = driver.ideFrame().editor("//div[@accessiblename='Rendered prompt preview']").waitFound(30.seconds)
        return driver.withContext(OnDispatcher.EDT) {
            field.text
        }
    }

    fun selectContextMenuItem(vararg path: String, item: String) {
        openContextMenu(*path)
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
        selectContextMenuItem(*parentPath.toTypedArray(), item = "New Folder")
        driver.ui.dialog(title = "New Prompt Template Folder") {
            waitFound(30.seconds)
            textField().text = name
            clickComponent(okButton)
        }
        driver.ui.waitForNoOpenedDialogs()
        waitForPath(*(parentPath + name).toTypedArray())
    }

    fun createDefaultTemplate(parentPath: List<String>, name: String) {
        selectContextMenuItem(*parentPath.toTypedArray(), item = "New Template")
        val frame = driver.ideFrame()
        val saveButton = frame.button("Save Template").waitFound(30.seconds)
        frame.textField { byVisibleText("New prompt") }.text = name
        clickComponent(saveButton)
        waitForPath(*(parentPath + name).toTypedArray())
    }

    fun movePathToFolder(sourcePath: List<String>, destinationPath: String) {
        selectContextMenuItem(*sourcePath.toTypedArray(), item = "Move to Folder…")
        driver.ui.popup().apply {
            waitFound(30.seconds)
            val destinations = list()
            val destinationIndex = destinations.items.indexOf(destinationPath)
            check(destinationIndex >= 0) {
                "Move destination '$destinationPath' not found in ${destinations.items}"
            }
            val destinationBounds = destinations.getCellBounds(destinationIndex)
            clickComponentAt(
                destinations,
                Point(
                    destinationBounds.x + destinationBounds.width / 2,
                    destinationBounds.y + destinationBounds.height / 2,
                ),
            )
            waitNotFound(30.seconds)
        }
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

    fun waitForAccessibleText(text: String) {
        driver.ui.x { contains(byAccessibleName(text)) }.waitFound(30.seconds)
    }

    fun waitForNotification(text: String) {
        waitFor("prompt notification contains $text", 30.seconds) {
            driver.getNotifications().any { it.getGroupId() == "Prompt Templates" && it.getContent().contains(text) }
        }
    }

    fun confirmTemplateOverwrite(review: (String) -> Unit) {
        driver.ui.dialog(title = "Prompt Template Changed") {
            waitFound(30.seconds)
            val current = x { and(byClass("JBTextArea"), byAccessibleName("Current files on disk")) }.waitFound()
            review(driver.cast(current.component, TestTextArea::class).getText())
            clickComponent(button("Overwrite with Draft"))
        }
        driver.ui.waitForNoOpenedDialogs()
    }

    fun chooseUnsavedDraftAction(action: String) {
        driver.ui.dialog(title = "Unsaved Template") {
            waitFound(30.seconds)
            clickComponent(button(action))
        }
        driver.ui.waitForNoOpenedDialogs()
    }

    fun setIdeWidth(width: Int) {
        driver.withContext(OnDispatcher.EDT) {
            cast(ideFrame().component, TestIdeFrame::class).setSize(width, 960)
        }
        waitFor("IDE width is $width", 30.seconds) { driver.ideFrame().boundsOnScreen.width == width }
    }

    fun hide() {
        driver.withContext(OnDispatcher.EDT) { getToolWindow("Prompt Templates").hide() }
    }

    fun toggleWordWrap() {
        clickComponent(driver.ideFrame().x { and(byClass("JBCheckBox"), byAccessibleName("Word wrap")) }.waitFound())
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

@Remote("java.awt.Toolkit")
private interface RemoteToolkit {
    fun getDefaultToolkit(): RemoteToolkit
    fun getSystemEventQueue(): RemoteEventQueue
}

@Remote("java.awt.EventQueue")
private interface RemoteEventQueue {
    fun postEvent(event: PopupTriggerEvent)
}

@Remote("com.intellij.openapi.wm.impl.ToolWindowImpl")
private interface TestToolWindow {
    fun stretchHeight(delta: Int)
}

@Remote("javax.swing.JTextArea")
internal interface TestTextArea {
    fun getText(): String
}

@Remote("java.awt.Frame")
private interface TestIdeFrame {
    fun setSize(width: Int, height: Int)
}
