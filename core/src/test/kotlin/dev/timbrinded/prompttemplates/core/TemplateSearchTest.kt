package dev.timbrinded.prompttemplates.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateSearchTest {
    @Test
    fun `text relevance outranks favourites and recents across every search field`() {
        val body = entry(1, "Writing", body = "Review the change")
        val tag = entry(2, "Code", tags = listOf("review"))
        val contains = entry(3, "Code review")
        val prefix = entry(4, "Review changes")
        val exact = entry(5, "Review")

        assertEquals(
            listOf(exact, prefix, contains, tag, body),
            TemplateSearch.ranked(listOf(body, tag, contains, prefix, exact), "REVIEW",
                favourites = setOf(body.summary.id!!.value), recents = listOf(tag.summary.id!!.value)),
        )
    }

    @Test
    fun `empty query prioritizes identities and duplicate names have deterministic path order`() {
        val first = entry(1, "Review", path = "a/review")
        val second = entry(2, "Review", path = "b/review")
        val third = entry(3, "Review", path = "c/review")

        assertEquals(listOf(first, second, third), TemplateSearch.ranked(listOf(third, second, first), "review"))
        assertEquals(listOf(third, second, first), TemplateSearch.ranked(listOf(first, third, second), "",
            favourites = setOf(third.summary.id!!.value), recents = listOf("missing", second.summary.id!!.value)))
    }

    @Test
    fun `all terms may match different fields and missing terms exclude the result`() {
        val match = entry(1, "Review", path = "backend/review", description = "Transaction correctness", body = "Investigate retries")

        assertEquals(listOf(match), TemplateSearch.ranked(listOf(match), "backend retries correctness"))
        assertEquals(emptyList(), TemplateSearch.ranked(listOf(match), "backend timeout"))
    }

    private fun entry(
        id: Int, name: String, path: String = name, tags: List<String> = emptyList(),
        description: String? = null, body: String = "",
    ) = TemplateSearchEntry(
        TemplateSummary(TemplateId("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"), name, description,
            tags, Path.of("library", path), TemplateHealth.HEALTHY),
        path, body,
    )
}
