package dev.timbrinded.prompttemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.core.PromptVariable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

private const val REMOVE_UNUSED_ACTION_KEY = "remove-unused-variable"
private val REMOVE_ACTION_WIDTH get() = JBUI.scale(32)

internal class UnusedVariableListActions(
    private val list: JList<PromptVariable>,
    private val isUnused: (PromptVariable) -> Boolean,
    private val onRemove: (PromptVariable) -> Unit,
) : MouseAdapter() {
    private var hoveredIndex = -1
    private var deleteHovered = false

    init {
        list.addMouseListener(this)
        list.addMouseMotionListener(this)
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), REMOVE_UNUSED_ACTION_KEY)
        list.actionMap.put(REMOVE_UNUSED_ACTION_KEY, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                removeSelectedUnused()
            }
        })
    }

    fun isRowHovered(index: Int): Boolean = index == hoveredIndex

    fun isDeleteHovered(index: Int): Boolean = index == hoveredIndex && deleteHovered

    override fun mouseMoved(event: MouseEvent) {
        updateHover(event.point)
    }

    override fun mouseExited(event: MouseEvent) {
        updateHover(null)
    }

    override fun mouseClicked(event: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 1) return
        val target = deleteTargetAt(event.point) ?: return
        updateHover(null)
        onRemove(target)
        event.consume()
    }

    internal fun removeSelectedUnused(): Boolean {
        val selected = list.selectedValue?.takeIf(isUnused) ?: return false
        updateHover(null)
        onRemove(selected)
        return true
    }

    internal fun deleteTargetAt(point: Point): PromptVariable? {
        val index = list.locationToIndex(point).takeIf { it >= 0 } ?: return null
        val bounds = list.getCellBounds(index, index)?.takeIf { it.contains(point) } ?: return null
        if (point.x < bounds.x + bounds.width - REMOVE_ACTION_WIDTH) return null
        return list.model.getElementAt(index).takeIf(isUnused)
    }

    private fun updateHover(point: Point?) {
        val previousIndex = hoveredIndex
        val index = point?.let(::rowAt) ?: -1
        hoveredIndex = index
        deleteHovered = point != null && deleteTargetAt(point) != null
        list.toolTipText = if (deleteHovered) {
            "Remove unused variable '${list.model.getElementAt(index).key}'"
        } else {
            null
        }
        previousIndex.takeIf { it >= 0 }?.let(::repaintRow)
        index.takeIf { it >= 0 && it != previousIndex }?.let(::repaintRow)
    }

    private fun rowAt(point: Point): Int {
        val index = list.locationToIndex(point).takeIf { it >= 0 } ?: return -1
        return index.takeIf { list.getCellBounds(index, index)?.contains(point) == true } ?: -1
    }

    private fun repaintRow(index: Int) {
        list.getCellBounds(index, index)?.let(list::repaint)
    }
}

internal class VariableRenderer(
    private val isUnused: (PromptVariable) -> Boolean,
    private val isRowHovered: (Int) -> Boolean,
    private val isDeleteHovered: (Int) -> Boolean,
) : JPanel(BorderLayout()), ListCellRenderer<PromptVariable> {
    private val activeDeleteIcon = AllIcons.Actions.GC
    private val inactiveDeleteIcon = IconLoader.getDisabledIcon(activeDeleteIcon)
    private val content = object : ColoredListCellRenderer<PromptVariable>() {
        override fun customizeCellRenderer(
            list: JList<out PromptVariable>,
            value: PromptVariable,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            append(value.key)
            append("  ${value.type.name.lowercase()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (isUnused(value)) append("  unused", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
    private val delete = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(REMOVE_ACTION_WIDTH, JBUI.scale(24))
        accessibleContext.accessibleName = "Remove unused variable"
    }

    init {
        isOpaque = true
        add(content, BorderLayout.CENTER)
        add(delete, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out PromptVariable>,
        value: PromptVariable,
        index: Int,
        selected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        content.getListCellRendererComponent(list, value, index, selected, cellHasFocus)
        background = content.background

        val unused = isUnused(value)
        delete.isVisible = unused
        delete.icon = if (isRowHovered(index)) activeDeleteIcon else inactiveDeleteIcon
        delete.toolTipText = "Remove unused variable '${value.key}'"
        delete.background = if (isDeleteHovered(index)) {
            JBUI.CurrentTheme.ActionButton.hoverBackground()
        } else {
            background
        }
        delete.isOpaque = isDeleteHovered(index)
        return this
    }
}
