package dev.timbrinded.prompttemplates.core

const val ATTACHMENTS_CONTEXT_KEY = "ide.attachments"
const val MAX_ATTACHMENT_BYTES = 256 * 1024
const val MAX_ATTACHMENTS_BYTES = 1024 * 1024
const val MAX_ATTACHMENTS = 16

data class ContextAttachment(
    val id: String,
    val title: String,
    val source: String,
    val capturedAt: String,
    val text: String,
) {
    val byteCount: Int = text.toByteArray(Charsets.UTF_8).size
}

/** A frozen provider value. Formatting happens before the existing renderer's single substitution. */
class ContextAttachments(items: List<ContextAttachment> = emptyList()) {
    val items: List<ContextAttachment> = items.toList()
    val byteCount: Int = items.sumOf(ContextAttachment::byteCount)

    init {
        require(items.size <= MAX_ATTACHMENTS) { "Select at most $MAX_ATTACHMENTS attachments." }
        require(items.map(ContextAttachment::id).distinct().size == items.size) { "An attachment source is included more than once." }
        items.firstOrNull { it.byteCount > MAX_ATTACHMENT_BYTES }?.let {
            throw IllegalArgumentException("'${it.title}' exceeds the 256 KiB attachment limit. No content was truncated.")
        }
        require(byteCount <= MAX_ATTACHMENTS_BYTES) { "Attachments exceed the 1 MiB total limit. Remove an item or select smaller sources." }
    }

    fun with(replacements: List<ContextAttachment>): ContextAttachments {
        val incoming = replacements.associateBy(ContextAttachment::id)
        val ids = items.mapTo(hashSetOf(), ContextAttachment::id)
        return ContextAttachments(items.map { incoming[it.id] ?: it } + replacements.filterNot { it.id in ids })
    }

    fun without(id: String): ContextAttachments = ContextAttachments(items.filterNot { it.id == id })

    fun contextValue(): ContextValue = if (items.isEmpty()) {
        ContextValue.unavailable("Add Context… to capture attachments, or remove {{ide.attachments}} from the template.")
    } else {
        ContextValue.available(items.mapIndexed { index, item ->
            val fence = "`".repeat(maxOf(3, BACKTICKS.findAll(item.text).maxOfOrNull { it.value.length + 1 } ?: 3))
            "## Attachment ${index + 1}: ${item.title.singleLine()}\n" +
                "${item.source.singleLine()}\nCaptured: ${item.capturedAt} | ${item.byteCount} UTF-8 bytes\n\n" +
                "$fence\n${item.text}${if (item.text.endsWith('\n')) "" else "\n"}$fence"
        }.joinToString("\n\n"), "${items.size} frozen items, $byteCount UTF-8 bytes. Sources may have changed; inspect or refresh in Add Context…")
    }

    private fun String.singleLine(): String = replace("\r", "\\r").replace("\n", "\\n")

    private companion object { val BACKTICKS = Regex("`+") }
}
