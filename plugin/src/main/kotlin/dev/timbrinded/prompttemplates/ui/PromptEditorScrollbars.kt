package dev.timbrinded.prompttemplates.ui

import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

internal fun configurePromptEditorScrollbars(scrollPane: JScrollPane) {
    scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
}
