package dev.timbrinded.prompttemplates.ui

import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptEditorScrollbarsTest {
    @Test
    fun `shows prompt editor scrollbars only when content overflows`() {
        val scrollPane = JScrollPane().apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        configurePromptEditorScrollbars(scrollPane)

        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, scrollPane.verticalScrollBarPolicy)
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, scrollPane.horizontalScrollBarPolicy)
    }
}
