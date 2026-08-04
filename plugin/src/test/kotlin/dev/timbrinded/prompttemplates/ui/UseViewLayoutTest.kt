package dev.timbrinded.prompttemplates.ui

import com.intellij.ui.OnePixelSplitter
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class UseViewLayoutTest {
    @Test
    fun `omits the variable pane when the template has no variables`() {
        val formPanel = JPanel()
        val previewPanel = JPanel()

        val content = createUseViewContent(false, formPanel, previewPanel)

        assertSame(previewPanel, content)
    }

    @Test
    fun `keeps the variable and preview splitter when variables exist`() {
        val content = createUseViewContent(true, JPanel(), JPanel())

        assertIs<OnePixelSplitter>(content)
    }

    @Test
    fun `keeps only primary workflow actions in the footer`() {
        assertEquals(
            listOf(UseViewAction.COPY_PROMPT, UseViewAction.INSERT, UseViewAction.EDIT),
            USE_VIEW_PRIMARY_ACTIONS,
        )
    }

    @Test
    fun `groups source and export actions in the file menu`() {
        assertEquals(
            listOf(
                UseViewAction.OPEN_MARKDOWN,
                UseViewAction.REVEAL,
                UseViewAction.COPY_PATH,
                UseViewAction.EXPORT_TEMPLATE,
                UseViewAction.EXPORT_RENDERED,
            ),
            USE_VIEW_FILE_ACTIONS,
        )
    }
}
