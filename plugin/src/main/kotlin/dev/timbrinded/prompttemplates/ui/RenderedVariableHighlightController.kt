package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.EditorTextField
import dev.timbrinded.prompttemplates.core.RenderResult
import dev.timbrinded.prompttemplates.core.SourceRange
import java.awt.Font

internal data class RenderedVariableHighlight(
    val key: String,
    val range: SourceRange,
    val accent: VariableAccent,
)

internal fun renderedVariableHighlights(
    result: RenderResult,
    accents: Map<String, VariableAccent>,
): List<RenderedVariableHighlight> = result.mappings.mapNotNull { mapping ->
    val key = mapping.key ?: return@mapNotNull null
    val accent = accents[key] ?: return@mapNotNull null
    if (mapping.output.start == mapping.output.endExclusive) return@mapNotNull null
    RenderedVariableHighlight(key, mapping.output, accent)
}

internal fun renderedVariableTextAttributes(accent: VariableAccent) = TextAttributes(
    accent.foreground,
    accent.background,
    null,
    null,
    Font.BOLD,
)

internal class RenderedVariableHighlightController(
    private val field: EditorTextField,
    private val accents: Map<String, VariableAccent>,
) : Disposable {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private var highlights = emptyList<RenderedVariableHighlight>()

    init {
        field.addSettingsProvider { editor -> applyHighlights(editor) }
    }

    fun update(result: RenderResult) {
        highlights = renderedVariableHighlights(result, accents)
        (field.editor as? EditorEx)?.let(::applyHighlights)
    }

    private fun applyHighlights(editor: EditorEx) {
        highlighters.forEach(RangeHighlighter::dispose)
        highlighters.clear()
        highlights.forEach { highlight ->
            highlighters += editor.markupModel.addRangeHighlighter(
                highlight.range.start,
                highlight.range.endExclusive,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                renderedVariableTextAttributes(highlight.accent),
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
    }

    override fun dispose() {
        highlighters.forEach(RangeHighlighter::dispose)
        highlighters.clear()
    }
}
