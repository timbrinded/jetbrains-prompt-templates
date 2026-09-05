package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class TemplateRevision internal constructor(val markdown: String?, val metadata: String?) {
    companion object {
        internal fun of(markdown: String?, metadata: String?): TemplateRevision =
            TemplateRevision(markdown?.let(::fingerprint), metadata?.let(::fingerprint))

        private fun fingerprint(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHexString()
    }
}

internal data class TemplateFiles(val markdown: String?, val metadata: String?) {
    val revision: TemplateRevision get() = TemplateRevision.of(markdown, metadata)
}

internal enum class TemplateSaveStep {
    BEFORE_STAGE, AFTER_STAGE, BEFORE_MARKDOWN, AFTER_MARKDOWN, BEFORE_METADATA, AFTER_METADATA,
}

internal class TemplateRevisionMismatch : IOException("The template changed while preparing the save.")

/** Caller holds the library lock. A published journal is an intent to finish the new pair. */
internal class TemplateFileStore(
    private val codec: TemplateMetadataCodec,
    private val onSaveStep: (TemplateSaveStep) -> Unit = {},
) {
    fun read(directory: Path): TemplateFiles {
        recover(directory)
        return readCanonical(directory)
    }

    fun save(directory: Path, template: PromptTemplate, expected: TemplateRevision): TemplateRevision {
        val journal = SaveJournal(expected, template.markdown, codec.encode(template.metadata))
        check(codec.decode(journal.metadata) is MetadataDecodeResult.Success)
        onSaveStep(TemplateSaveStep.BEFORE_STAGE)
        if (readCanonical(directory).revision != expected) {
            throw TemplateRevisionMismatch()
        }
        replaceAtomically(directory.resolve(JOURNAL_FILE), Json.encodeToString(journal))
        onSaveStep(TemplateSaveStep.AFTER_STAGE)
        finish(directory, journal, onSaveStep)
        return TemplateRevision.of(journal.markdown, journal.metadata)
    }

    fun recover(directory: Path) {
        val path = directory.resolve(JOURNAL_FILE)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        try {
            val journal = Json.decodeFromString<SaveJournal>(requireNotNull(readRegular(path)))
            if (journal.version != 1 || codec.decode(journal.metadata) !is MetadataDecodeResult.Success) {
                throw IOException("Unsupported or invalid save journal.")
            }
            finish(directory, journal)
        } catch (error: SerializationException) {
            throw IOException("Save recovery needs attention: invalid journal. Keep the journal and both canonical files. Journal: '$path'.", error)
        } catch (error: IOException) {
            throw IOException("Save recovery needs attention: ${error.message} Keep the journal and both canonical files. Journal: '$path'.", error)
        }
    }

    private fun finish(
        directory: Path,
        journal: SaveJournal,
        step: (TemplateSaveStep) -> Unit = {},
    ) {
        val next = TemplateRevision.of(journal.markdown, journal.metadata)
        fun checkedCurrent(): TemplateFiles {
            val current = readCanonical(directory)
            val revision = current.revision
            if (revision.markdown !in setOf(journal.before.markdown, next.markdown) ||
                revision.metadata !in setOf(journal.before.metadata, next.metadata)
            ) {
                throw IOException("A canonical file contains a later external change. No further files were replaced.")
            }
            return current
        }
        step(TemplateSaveStep.BEFORE_MARKDOWN)
        if (checkedCurrent().revision.markdown != next.markdown) {
            replaceAtomically(directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE), journal.markdown)
        }
        step(TemplateSaveStep.AFTER_MARKDOWN)
        step(TemplateSaveStep.BEFORE_METADATA)
        if (checkedCurrent().revision.metadata != next.metadata) {
            replaceAtomically(directory.resolve(FileSystemPromptTemplateRepository.METADATA_FILE), journal.metadata)
        }
        step(TemplateSaveStep.AFTER_METADATA)
        if (checkedCurrent().revision != next) throw IOException("The template changed before save completion.")
        Files.delete(directory.resolve(JOURNAL_FILE))
    }

    private fun readCanonical(directory: Path) = TemplateFiles(
        readRegular(directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)),
        readRegular(directory.resolve(FileSystemPromptTemplateRepository.METADATA_FILE)),
    )

    private fun readRegular(path: Path): String? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("${path.fileName} is not a regular file.")
        return Files.readString(path, Charsets.UTF_8)
    }

    private fun replaceAtomically(path: Path, text: String) {
        val temporary = Files.createTempFile(path.parent, STAGE_PREFIX, ".tmp")
        try {
            Files.writeString(temporary, text, Charsets.UTF_8)
            // If atomic replacement is unsupported, stop with the journal intact before risking a partial file.
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Serializable
    private data class SaveJournal(
        val before: TemplateRevision,
        val markdown: String,
        val metadata: String,
        val version: Int = 1,
    )

    companion object {
        const val JOURNAL_FILE = ".prompt-template-save.json"
        const val STAGE_PREFIX = ".prompt-template-stage-"
    }
}
