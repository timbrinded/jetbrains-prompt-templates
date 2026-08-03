package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.EditorTextField
import com.intellij.util.Alarm
import dev.timbrinded.prompttemplates.core.BUILT_IN_CONTEXT_KEYS
import dev.timbrinded.prompttemplates.core.LinearPlaceholderParser

class PlaceholderHighlightController(
    private val field: EditorTextField,
    private val parentDisposable: Disposable,
    private val onParsed: () -> Unit,
) {
    private val parser = LinearPlaceholderParser()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private val highlighters = mutableListOf<RangeHighlighter>()

    init {
        field.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule()
        }, parentDisposable)
        schedule()
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ update() }, 100)
    }

    private fun update() {
        val editor = field.editor as? EditorEx
        if (editor == null) {
            alarm.addRequest({ update() }, 100)
            return
        }
        highlighters.forEach(RangeHighlighter::dispose)
        highlighters.clear()

        val parsed = parser.parse(field.text)
        parsed.placeholders.forEach { token ->
            val attributes = when {
                token.contextReference && token.key !in BUILT_IN_CONTEXT_KEYS -> CodeInsightColors.WARNINGS_ATTRIBUTES
                token.contextReference -> EditorColors.SEARCH_RESULT_ATTRIBUTES
                else -> EditorColors.SEARCH_RESULT_ATTRIBUTES
            }
            highlighters += editor.markupModel.addRangeHighlighter(
                attributes,
                token.range.start,
                token.range.endExclusive,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
        parsed.diagnostics.forEach { diagnostic ->
            highlighters += editor.markupModel.addRangeHighlighter(
                CodeInsightColors.ERRORS_ATTRIBUTES,
                diagnostic.range.start,
                diagnostic.range.endExclusive,
                HighlighterLayer.ERROR,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
        onParsed()
    }
}
