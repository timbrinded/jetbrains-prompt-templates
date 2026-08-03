package dev.timbrinded.prompttemplates.core

data class PlaceholderToken(
    val key: String,
    val range: SourceRange,
    val contextReference: Boolean,
)

data class ParseResult(
    val placeholders: List<PlaceholderToken>,
    val escapedOpenings: List<SourceRange>,
    val diagnostics: List<TemplateDiagnostic.SyntaxError>,
) {
    val referencedKeys: List<String>
        get() = placeholders.map(PlaceholderToken::key).distinct()
}

fun interface PlaceholderParser {
    fun parse(markdown: String): ParseResult
}

class LinearPlaceholderParser : PlaceholderParser {
    override fun parse(markdown: String): ParseResult {
        val placeholders = mutableListOf<PlaceholderToken>()
        val escapedOpenings = mutableListOf<SourceRange>()
        val diagnostics = mutableListOf<TemplateDiagnostic.SyntaxError>()
        var index = 0

        while (index < markdown.length) {
            if (markdown[index] == '\\' && markdown.startsWith("{{", index + 1)) {
                escapedOpenings += SourceRange(index, index + 3)
                index += 3
                continue
            }

            if (!markdown.startsWith("{{", index)) {
                index++
                continue
            }

            val closing = markdown.indexOf("}}", startIndex = index + 2)
            if (closing < 0) {
                diagnostics += TemplateDiagnostic.SyntaxError(
                    SourceRange(index, markdown.length),
                    "Placeholder opened here is missing closing braces.",
                )
                break
            }

            val endExclusive = closing + 2
            val key = markdown.substring(index + 2, closing).trim(' ', '\t')
            if (key.isEmpty()) {
                diagnostics += TemplateDiagnostic.SyntaxError(
                    SourceRange(index, endExclusive),
                    "Placeholder key cannot be empty.",
                )
            } else if (!PLACEHOLDER_KEY_REGEX.matches(key)) {
                diagnostics += TemplateDiagnostic.SyntaxError(
                    SourceRange(index, endExclusive),
                    "Invalid placeholder key '$key'.",
                )
            } else {
                placeholders += PlaceholderToken(
                    key = key,
                    range = SourceRange(index, endExclusive),
                    contextReference = key.contains('.') || key == "clipboard",
                )
            }
            index = endExclusive
        }

        return ParseResult(placeholders, escapedOpenings, diagnostics)
    }
}
