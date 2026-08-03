package dev.timbrinded.prompttemplates.core

object TemplateSearch {
    fun matches(summary: TemplateSummary, query: String, body: String? = null): Boolean {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (terms.isEmpty()) return true
        val haystack = buildString {
            append(summary.name).append('\n')
            append(summary.description.orEmpty()).append('\n')
            append(summary.tags.joinToString(" ")).append('\n')
            append(body.orEmpty())
        }.lowercase()
        return terms.all(haystack::contains)
    }
}
