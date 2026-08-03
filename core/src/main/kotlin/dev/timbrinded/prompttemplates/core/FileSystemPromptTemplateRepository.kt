package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class FileSystemPromptTemplateRepository(
    override val root: Path,
    private val codec: TemplateMetadataCodec = TemplateMetadataCodec(),
    private val parser: PlaceholderParser = LinearPlaceholderParser(),
) : PromptTemplateRepository {
    override fun scan(): List<TemplateSummary> {
        if (!Files.exists(root)) return emptyList()
        if (!Files.isDirectory(root)) {
            return listOf(
                TemplateSummary(
                    id = null,
                    name = root.fileName.toString(),
                    description = null,
                    tags = emptyList(),
                    directory = root,
                    health = TemplateHealth.BROKEN,
                    diagnostic = "The configured library path is not a directory.",
                ),
            )
        }

        val summaries = try {
            Files.list(root).use { children ->
                children
                    .filter { Files.isDirectory(it) }
                    .map(::summaryFor)
                    .toList()
            }
        } catch (error: IOException) {
            return listOf(
                TemplateSummary(
                    id = null,
                    name = root.fileName?.toString() ?: root.toString(),
                    description = null,
                    tags = emptyList(),
                    directory = root,
                    health = TemplateHealth.BROKEN,
                    diagnostic = "Unable to read the template library: ${error.message}",
                ),
            )
        }

        val duplicateIds = summaries.mapNotNull(TemplateSummary::id)
            .groupingBy(TemplateId::value)
            .eachCount()
            .filterValues { it > 1 }
            .keys

        return summaries
            .map { summary ->
                if (summary.id?.value in duplicateIds) {
                    summary.copy(
                        health = TemplateHealth.BROKEN,
                        diagnostic = "Duplicate template UUID ${summary.id?.value}.",
                    )
                } else {
                    summary
                }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override fun load(directory: Path): RepositoryResult<StoredTemplate> = protect("load template") {
        val safeDirectory = requireTemplateDirectory(directory)
        val markdownPath = safeDirectory.resolve(MARKDOWN_FILE)
        if (!Files.isRegularFile(markdownPath)) {
            return@protect RepositoryResult.Failure("Template is missing $MARKDOWN_FILE.")
        }
        val markdown = Files.readString(markdownPath, StandardCharsets.UTF_8)
        val metadataPath = safeDirectory.resolve(METADATA_FILE)

        if (!Files.isRegularFile(metadataPath)) {
            val inferred = inferredMetadata(safeDirectory, markdown)
            return@protect RepositoryResult.Success(
                StoredTemplate(PromptTemplate(inferred, markdown), safeDirectory, recoverable = true),
            )
        }

        when (val decoded = codec.decode(Files.readString(metadataPath, StandardCharsets.UTF_8))) {
            is MetadataDecodeResult.Success -> RepositoryResult.Success(
                StoredTemplate(PromptTemplate(decoded.metadata, markdown), safeDirectory),
            )
            is MetadataDecodeResult.Invalid -> RepositoryResult.Failure(decoded.message, decoded.cause)
            is MetadataDecodeResult.UnsupportedVersion -> RepositoryResult.Failure(
                "Metadata schema ${decoded.found} is newer than supported schema $CURRENT_SCHEMA_VERSION.",
            )
        }
    }

    override fun create(draft: PromptTemplateDraft): RepositoryResult<StoredTemplate> = protect("create template") {
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@protect RepositoryResult.Failure(it) }
        Files.createDirectories(root)
        val directory = nextAvailableDirectory(slugify(template.metadata.name))
        Files.createDirectory(directory)
        writeTemplate(directory, template)
        RepositoryResult.Success(StoredTemplate(template, directory))
    }

    override fun update(
        directory: Path,
        draft: PromptTemplateDraft,
    ): RepositoryResult<StoredTemplate> = protect("update template") {
        val safeDirectory = requireTemplateDirectory(directory)
        val template = draft.toTemplate()
        codec.validate(template.metadata)?.let { return@protect RepositoryResult.Failure(it) }
        writeTemplate(safeDirectory, template)
        RepositoryResult.Success(StoredTemplate(template, safeDirectory))
    }

    override fun delete(directory: Path): RepositoryResult<Unit> = protect("delete template") {
        val safeDirectory = requireTemplateDirectory(directory)
        Files.walk(safeDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        RepositoryResult.Success(Unit)
    }

    override fun importMarkdown(source: Path): RepositoryResult<StoredTemplate> = protect("import Markdown") {
        if (!Files.isRegularFile(source) || source.extension.lowercase() != "md") {
            return@protect RepositoryResult.Failure("Select a Markdown (.md) file.")
        }
        val markdown = Files.readString(source, StandardCharsets.UTF_8)
        val inferredName = firstHeading(markdown) ?: source.nameWithoutExtension
        val variables = parser.parse(markdown).placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .distinct()
            .map { key -> PromptVariable(key = key, label = defaultVariableLabel(key)) }
        create(
            PromptTemplateDraft(
                name = inferredName,
                variables = variables,
                markdown = markdown,
            ),
        )
    }

    override fun exportTemplateMarkdown(
        directory: Path,
        destination: Path,
    ): RepositoryResult<Path> = protect("export template Markdown") {
        val source = requireTemplateDirectory(directory).resolve(MARKDOWN_FILE)
        if (!Files.isRegularFile(source)) {
            return@protect RepositoryResult.Failure("Template is missing $MARKDOWN_FILE.")
        }
        ensureDestinationParent(destination)
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        RepositoryResult.Success(destination)
    }

    override fun exportRenderedMarkdown(
        rendered: String,
        destination: Path,
    ): RepositoryResult<Path> = protect("export rendered Markdown") {
        ensureDestinationParent(destination)
        atomicWrite(destination, rendered)
        RepositoryResult.Success(destination)
    }

    private fun summaryFor(directory: Path): TemplateSummary {
        val markdownExists = Files.isRegularFile(directory.resolve(MARKDOWN_FILE))
        val metadataPath = directory.resolve(METADATA_FILE)
        if (!Files.isRegularFile(metadataPath)) {
            return TemplateSummary(
                id = null,
                name = directory.fileName.toString(),
                description = null,
                tags = emptyList(),
                directory = directory,
                health = if (markdownExists) TemplateHealth.RECOVERABLE else TemplateHealth.BROKEN,
                diagnostic = if (markdownExists) {
                    "Metadata is missing and can be regenerated."
                } else {
                    "Template is missing both canonical files."
                },
            )
        }

        val decoded = runCatching {
            codec.decode(Files.readString(metadataPath, StandardCharsets.UTF_8))
        }.getOrElse { error ->
            return TemplateSummary(
                null,
                directory.fileName.toString(),
                null,
                emptyList(),
                directory,
                TemplateHealth.BROKEN,
                "Unable to read metadata: ${error.message}",
            )
        }

        return when (decoded) {
            is MetadataDecodeResult.Success -> TemplateSummary(
                id = TemplateId(decoded.metadata.id),
                name = decoded.metadata.name,
                description = decoded.metadata.description,
                tags = decoded.metadata.tags,
                directory = directory,
                health = if (markdownExists) TemplateHealth.HEALTHY else TemplateHealth.BROKEN,
                diagnostic = if (markdownExists) null else "Template is missing $MARKDOWN_FILE.",
            )
            is MetadataDecodeResult.Invalid -> TemplateSummary(
                null,
                directory.fileName.toString(),
                null,
                emptyList(),
                directory,
                TemplateHealth.BROKEN,
                decoded.message,
            )
            is MetadataDecodeResult.UnsupportedVersion -> TemplateSummary(
                null,
                directory.fileName.toString(),
                null,
                emptyList(),
                directory,
                TemplateHealth.BROKEN,
                "Unsupported metadata schema ${decoded.found}; opened read-only.",
            )
        }
    }

    private fun inferredMetadata(directory: Path, markdown: String): TemplateMetadata {
        val id = UUID.nameUUIDFromBytes(directory.toAbsolutePath().normalize().toString().toByteArray()).toString()
        val variables = parser.parse(markdown).placeholders
            .filterNot(PlaceholderToken::contextReference)
            .map(PlaceholderToken::key)
            .distinct()
            .map { key -> PromptVariable(key = key, label = defaultVariableLabel(key)) }
        return TemplateMetadata(
            id = id,
            name = firstHeading(markdown) ?: directory.fileName.toString(),
            variables = variables,
        )
    }

    private fun writeTemplate(directory: Path, template: PromptTemplate) {
        atomicWrite(directory.resolve(MARKDOWN_FILE), template.markdown)
        atomicWrite(directory.resolve(METADATA_FILE), codec.encode(template.metadata))
    }

    private fun atomicWrite(destination: Path, content: String) {
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireTemplateDirectory(directory: Path): Path {
        val normalRoot = root.toAbsolutePath().normalize()
        val normalDirectory = directory.toAbsolutePath().normalize()
        require(normalDirectory.parent == normalRoot) { "Template must be a direct child of the library root." }
        require(Files.isDirectory(normalDirectory)) { "Template directory does not exist." }
        require(!Files.isSymbolicLink(normalDirectory)) { "Symbolic-link template directories are not supported." }
        return normalDirectory
    }

    private fun nextAvailableDirectory(base: String): Path {
        var candidate = root.resolve(base)
        var suffix = 2
        while (Files.exists(candidate)) {
            candidate = root.resolve("$base-$suffix")
            suffix++
        }
        return candidate
    }

    private fun ensureDestinationParent(destination: Path) {
        destination.parent?.let(Files::createDirectories)
    }

    private fun slugify(name: String): String = name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "prompt-template" }

    private fun firstHeading(markdown: String): String? = markdown.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.ifEmpty { null }

    private inline fun <T> protect(operation: String, block: () -> RepositoryResult<T>): RepositoryResult<T> = try {
        block()
    } catch (error: IOException) {
        RepositoryResult.Failure("Unable to $operation: ${error.message}", error)
    } catch (error: IllegalArgumentException) {
        RepositoryResult.Failure(error.message ?: "Unable to $operation.", error)
    } catch (error: SecurityException) {
        RepositoryResult.Failure("Unable to $operation: permission denied.", error)
    }

    companion object {
        const val MARKDOWN_FILE = "prompt.md"
        const val METADATA_FILE = "prompt.meta.json"
    }
}
