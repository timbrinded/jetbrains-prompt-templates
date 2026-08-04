package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.PromptVariable
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnusedVariableListActionsTest {
    @Test
    fun `trash click removes only an unused variable`() {
        SwingUtilities.invokeAndWait {
            val used = PromptVariable("used", "Used")
            val unused = PromptVariable("unused", "Unused")
            val list = configuredList(used, unused)
            val removed = mutableListOf<PromptVariable>()
            val actions = UnusedVariableListActions(list, { it == unused }, removed::add)

            assertNull(actions.deleteTargetAt(Point(190, 10)))
            assertEquals(unused, actions.deleteTargetAt(Point(190, 34)))
            actions.mouseClicked(clickAt(list, 190, 10))
            actions.mouseClicked(clickAt(list, 190, 34))
            actions.mouseClicked(clickAt(list, 190, 34, clickCount = 2))

            assertEquals(listOf(unused), removed)
        }
    }

    @Test
    fun `Delete removes only the selected unused variable`() {
        SwingUtilities.invokeAndWait {
            val used = PromptVariable("used", "Used")
            val unused = PromptVariable("unused", "Unused")
            val list = configuredList(used, unused)
            val removed = mutableListOf<PromptVariable>()
            val actions = UnusedVariableListActions(list, { it == unused }, removed::add)

            list.selectedIndex = 0
            assertFalse(actions.removeSelectedUnused())
            list.selectedIndex = 1
            assertTrue(actions.removeSelectedUnused())
            assertEquals(listOf(unused), removed)
        }
    }

    @Test
    fun `trash target is limited to the icon area`() {
        SwingUtilities.invokeAndWait {
            val unused = PromptVariable("unused", "Unused")
            val list = configuredList(unused)
            val actions = UnusedVariableListActions(list, { true }) {}

            assertNull(actions.deleteTargetAt(Point(120, 10)))
            assertEquals(unused, actions.deleteTargetAt(Point(190, 10)))
        }
    }

    private fun clickAt(
        list: JList<PromptVariable>,
        x: Int,
        y: Int,
        clickCount: Int = 1,
    ) = MouseEvent(
        list,
        MouseEvent.MOUSE_CLICKED,
        0,
        0,
        x,
        y,
        clickCount,
        false,
        MouseEvent.BUTTON1,
    )

    private fun configuredList(vararg variables: PromptVariable): JList<PromptVariable> =
        JList(variables).apply {
            fixedCellHeight = 24
            fixedCellWidth = 200
            setSize(200, variables.size * fixedCellHeight)
        }
}
