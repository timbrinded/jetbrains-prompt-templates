package dev.timbrinded.prompttemplates.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

private val USE_VIEW_GAP get() = JBUI.scale(8)
private val MINIMUM_FORM_HEIGHT get() = JBUI.scale(64)
private val MINIMUM_PREVIEW_HEIGHT get() = JBUI.scale(160)

internal class ResponsiveActionsPanel(
    private val alignment: Int = FlowLayout.LEFT,
) : JPanel(FlowLayout(alignment, JBUI.scale(6), 0)) {
    override fun doLayout() {
        val rowWidth = components.sumOf { it.preferredSize.width } + JBUI.scale(6) * (componentCount + 1)
        val stacked = width < rowWidth
        if (stacked != (layout is GridLayout)) {
            layout = if (stacked) GridLayout(0, 1, 0, JBUI.scale(6))
            else FlowLayout(alignment, JBUI.scale(6), 0)
            revalidate()
        }
        super.doLayout()
    }
}

internal fun createUseViewContent(
    hasVariables: Boolean,
    formPanel: JComponent,
    previewPanel: JComponent,
): JComponent {
    if (!hasVariables) return previewPanel

    return UseViewContentPanel(formPanel, previewPanel)
}

internal fun useViewFormHeight(
    availableHeight: Int,
    preferredFormHeight: Int,
    minimumFormHeight: Int,
    minimumPreviewHeight: Int,
    gap: Int,
): Int {
    val availableFormHeight = (availableHeight - gap).coerceAtLeast(0)
    val targetHeight = (availableHeight - minimumPreviewHeight - gap)
        .coerceAtLeast(minOf(preferredFormHeight, minimumFormHeight))
    return targetHeight.coerceIn(0, minOf(preferredFormHeight, availableFormHeight))
}

private class UseViewContentPanel(
    private val formPanel: JComponent,
    private val previewPanel: JComponent,
) : JPanel(null) {
    init {
        add(formPanel)
        add(previewPanel)
    }

    override fun doLayout() {
        val availableWidth = (width - insets.left - insets.right).coerceAtLeast(0)
        val availableHeight = (height - insets.top - insets.bottom).coerceAtLeast(0)
        val formHeight = useViewFormHeight(
            availableHeight = availableHeight,
            preferredFormHeight = formPanel.preferredSize.height,
            minimumFormHeight = MINIMUM_FORM_HEIGHT,
            minimumPreviewHeight = MINIMUM_PREVIEW_HEIGHT,
            gap = USE_VIEW_GAP,
        )
        val gap = USE_VIEW_GAP.takeIf { formHeight > 0 } ?: 0
        val previewY = insets.top + formHeight + gap

        formPanel.setBounds(insets.left, insets.top, availableWidth, formHeight)
        previewPanel.setBounds(
            insets.left,
            previewY,
            availableWidth,
            (availableHeight - formHeight - gap).coerceAtLeast(0),
        )
    }

    override fun getPreferredSize(): Dimension = Dimension(
        maxOf(formPanel.preferredSize.width, previewPanel.preferredSize.width) + insets.left + insets.right,
        formPanel.preferredSize.height + USE_VIEW_GAP + previewPanel.preferredSize.height + insets.top + insets.bottom,
    )

    override fun getMinimumSize(): Dimension = Dimension(
        maxOf(formPanel.minimumSize.width, previewPanel.minimumSize.width) + insets.left + insets.right,
        MINIMUM_FORM_HEIGHT + USE_VIEW_GAP + MINIMUM_PREVIEW_HEIGHT + insets.top + insets.bottom,
    )
}
