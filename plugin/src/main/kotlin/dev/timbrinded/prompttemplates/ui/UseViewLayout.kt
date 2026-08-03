package dev.timbrinded.prompttemplates.ui

import com.intellij.ui.OnePixelSplitter
import javax.swing.JComponent

internal fun createUseViewContent(
    hasVariables: Boolean,
    formPanel: JComponent,
    previewPanel: JComponent,
): JComponent {
    if (!hasVariables) return previewPanel

    return OnePixelSplitter(true, 0.48f).apply {
        firstComponent = formPanel
        secondComponent = previewPanel
    }
}
