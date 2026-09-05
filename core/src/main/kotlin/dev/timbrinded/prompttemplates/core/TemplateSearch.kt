package dev.timbrinded.prompttemplates.core

object TemplateSearch {
    private val whitespace = Regex("\\s+")

    /** The lowercased searchable text of one template; callers split the query and test each term against it. */
    fun haystack(summary: TemplateSummary, body: String? = null): String = buildString {
        append(summary.name).append('\n')
        append(summary.description.orEmpty()).append('\n')
        append(summary.tags.joinToString(" ")).append('\n')
        append(body.orEmpty())
    }.lowercase()

    fun ranked(
        entries: List<TemplateSearchEntry>,
        query: String,
        favourites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
    ): List<TemplateSearchEntry> {
        val normalized = query.trim().lowercase()
        val terms = normalized.split(whitespace).filter(String::isNotEmpty)
        val recentOrder = recents.withIndex().associate { it.value to it.index }
        return entries.mapNotNull { entry ->
            val rank = when {
                terms.isEmpty() -> 0
                entry.name == normalized -> 0
                entry.name.startsWith(normalized) -> 1
                terms.all(entry.name::contains) -> 2
                terms.all { entry.name.contains(it) || entry.tagsAndPath.contains(it) } -> 3
                terms.all { entry.name.contains(it) || entry.tagsAndPath.contains(it) || entry.descriptionAndBody.contains(it) } -> 4
                else -> return@mapNotNull null
            }
            entry to rank
        }.sortedWith(
            compareBy<Pair<TemplateSearchEntry, Int>> { it.second }
                .thenBy { it.first.summary.id?.value !in favourites }
                .thenBy { recentOrder[it.first.summary.id?.value] ?: Int.MAX_VALUE }
                .thenBy { it.first.name }
                .thenBy { it.first.relativePath }
                .thenBy { it.first.summary.id?.value.orEmpty() },
        ).map { it.first }
    }
}

/** Normalize searchable content once when the library is loaded, not on each keystroke. */
class TemplateSearchEntry(
    val summary: TemplateSummary,
    val relativePath: String,
    body: String,
) {
    internal val name = summary.name.lowercase()
    internal val tagsAndPath = (summary.tags.joinToString(" ") + "\n" + relativePath).lowercase()
    internal val descriptionAndBody = (summary.description.orEmpty() + "\n" + body).lowercase()
}
