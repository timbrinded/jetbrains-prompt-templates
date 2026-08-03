package dev.timbrinded.prompttemplates.ui

import com.intellij.ui.OnePixelSplitter
import javax.swing.JPanel
import kotlin.test.Test
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
}
