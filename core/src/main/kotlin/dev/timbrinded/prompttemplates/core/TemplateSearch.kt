package dev.timbrinded.prompttemplates.core

object TemplateSearch {
    /** The lowercased searchable text of one template; callers split the query and test each term against it. */
    fun haystack(summary: TemplateSummary, body: String? = null): String = buildString {
        append(summary.name).append('\n')
        append(summary.description.orEmpty()).append('\n')
        append(summary.tags.joinToString(" ")).append('\n')
        append(body.orEmpty())
    }.lowercase()
}
