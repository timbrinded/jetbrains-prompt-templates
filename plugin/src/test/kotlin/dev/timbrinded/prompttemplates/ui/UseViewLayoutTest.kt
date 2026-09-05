package dev.timbrinded.prompttemplates.ui

import java.awt.Dimension
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `sizes a short variable form to its preferred height`() {
        val formPanel = JPanel().apply { preferredSize = Dimension(300, 72) }
        val previewPanel = JPanel()
        val content = createUseViewContent(true, formPanel, previewPanel)

        content.setSize(400, 500)
        content.doLayout()

        assertEquals(72, formPanel.height)
        assertEquals(420, previewPanel.height)
    }

    @Test
    fun `caps a long variable form to preserve the preview`() {
        val formPanel = JPanel().apply { preferredSize = Dimension(300, 480) }
        val previewPanel = JPanel()
        val content = createUseViewContent(true, formPanel, previewPanel)

        content.setSize(400, 500)
        content.doLayout()

        assertEquals(332, formPanel.height)
        assertEquals(160, previewPanel.height)
    }

    @Test
    fun `keeps the variable form usable when the view is short`() {
        assertEquals(
            64,
            useViewFormHeight(
                availableHeight = 200,
                preferredFormHeight = 480,
                minimumFormHeight = 64,
                minimumPreviewHeight = 160,
                gap = 8,
            ),
        )
    }

    @Test
    fun `keeps only primary workflow actions in the footer`() {
        assertEquals(
            listOf(UseViewAction.COPY_PROMPT, UseViewAction.INSERT, UseViewAction.EDIT),
            USE_VIEW_PRIMARY_ACTIONS,
        )
    }

    @Test
    fun `groups invocation source and export actions in the file menu`() {
        assertEquals(
            listOf(
                UseViewAction.REFRESH_CONTEXT,
                UseViewAction.RELOAD_TEMPLATE,
                UseViewAction.SELECT_INSERTION_TARGET,
                UseViewAction.RESET_VALUES,
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
