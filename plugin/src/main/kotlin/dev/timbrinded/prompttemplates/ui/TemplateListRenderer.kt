package dev.timbrinded.prompttemplates.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.timbrinded.prompttemplates.core.TemplateHealth
import dev.timbrinded.prompttemplates.core.TemplateSummary
import javax.swing.JList

class TemplateListRenderer : ColoredListCellRenderer<TemplateSummary>() {
    override fun customizeCellRenderer(
        list: JList<out TemplateSummary>,
        value: TemplateSummary,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.name)
        val secondary = when {
            value.health == TemplateHealth.BROKEN -> "  Broken"
            value.health == TemplateHealth.RECOVERABLE -> "  Metadata missing"
            value.tags.isNotEmpty() -> "  ${value.tags.joinToString(", ")}"
            else -> null
        }
        secondary?.let { append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        toolTipText = value.diagnostic ?: value.description
        accessibleContext.accessibleName = value.name
    }
}
