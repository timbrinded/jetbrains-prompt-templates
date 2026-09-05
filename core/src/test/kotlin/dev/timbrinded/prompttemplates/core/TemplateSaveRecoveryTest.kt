package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TemplateSaveRecoveryTest(@param:TempDir private val temporary: Path) {
    @Test
    fun `exception at every commit boundary retains either old files or a recoverable new pair`() {
        for (step in TemplateSaveStep.entries) {
            val root = temporary.resolve(step.name)
            val repository = FileSystemPromptTemplateRepository(root)
            val original = createOriginal(repository)
            val codec = TemplateMetadataCodec()
            val failing = FileSystemPromptTemplateRepository(root, codec, LinearPlaceholderParser(), TemplateFileStore(codec) {
                if (it == step) throw IOException("Injected $step")
            })
            assertIs<RepositoryResult.Failure>(failing.update(original.directory, changed(original), original.revision))
            val loaded = load(repository, original.directory)
            val committed = step != TemplateSaveStep.BEFORE_STAGE
            assertEquals(if (committed) "New body" else "Old body", loaded.template.markdown, step.name)
            assertEquals(if (committed) "Changed" else "Original", loaded.template.metadata.name, step.name)
            assertFalse(original.directory.resolve(TemplateFileStore.JOURNAL_FILE).exists())
        }
    }

    @Test
    fun `a killed writer recovers after reopening at every commit boundary`() {
        for (step in TemplateSaveStep.entries) {
            val root = temporary.resolve(step.name)
            val original = createOriginal(FileSystemPromptTemplateRepository(root))
            val child = startProcess(root, "crash", step.name)
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), step.name)
            assertEquals(23, child.exitValue(), step.name)
            val reopened = FileSystemPromptTemplateRepository(root)
            val summary = assertIs<LibraryEntry.Template>(reopened.scan().children.single()).summary
            val loaded = load(reopened, original.directory)
            assertEquals(TemplateHealth.HEALTHY, summary.health, step.name)
            assertEquals(if (step == TemplateSaveStep.BEFORE_STAGE) "Old body" else "New body", loaded.template.markdown, step.name)
            assertEquals(if (step == TemplateSaveStep.BEFORE_STAGE) "Original" else "Changed", summary.name, step.name)
            assertFalse(original.directory.resolve(TemplateFileStore.JOURNAL_FILE).exists())
        }
    }

    @Test
    fun `recovery preserves later external changes and keeps the journal for repair`() {
        val root = temporary.resolve("library")
        val repository = FileSystemPromptTemplateRepository(root)
        val original = createOriginal(repository)
        val child = startProcess(root, "crash", TemplateSaveStep.AFTER_MARKDOWN.name)
        assertTrue(child.waitFor(15, TimeUnit.SECONDS))
        assertEquals(23, child.exitValue())
        val journal = original.directory.resolve(TemplateFileStore.JOURNAL_FILE)
        val retained = journal.readText()
        val metadata = original.directory.resolve(FileSystemPromptTemplateRepository.METADATA_FILE)
        val originalMetadata = metadata.readText()
        metadata.writeText(originalMetadata + "\n") // An external edit, even with the same decoded metadata, must win.
        val failure = assertIs<RepositoryResult.Failure>(repository.load(original.directory))
        assertTrue(failure.message.contains("later external change"))
        assertEquals(originalMetadata + "\n", metadata.readText())
        assertEquals("New body", original.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE).readText())
        assertEquals(retained, journal.readText())
        val summary = assertIs<LibraryEntry.Template>(repository.scan().children.single()).summary
        assertEquals(TemplateHealth.BROKEN, summary.health)
        assertTrue(summary.diagnostic.orEmpty().contains("Save recovery needs attention"))
        // Explicitly restoring a known revision makes deterministic recovery safe again.
        metadata.writeText(originalMetadata)
        assertEquals("Changed", load(repository, original.directory).template.metadata.name)
        assertFalse(journal.exists())
    }

    @Test
    fun `stale updates and stale overwrite decisions return typed conflicts without writing`() {
        val repository = FileSystemPromptTemplateRepository(temporary.resolve("library"))
        val original = createOriginal(repository)
        val path = original.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE)
        path.writeText("External one")
        val first = assertIs<RepositoryResult.Conflict>(repository.update(original.directory, changed(original), original.revision))
        assertEquals("External one", first.current.template.markdown)
        path.writeText("External two")
        val second = assertIs<RepositoryResult.Conflict>(repository.update(original.directory, changed(original), first.current.revision))
        assertEquals("External two", path.readText())
        assertFalse(original.directory.resolve(TemplateFileStore.JOURNAL_FILE).exists())
        assertIs<RepositoryResult.Success<StoredTemplate>>(repository.update(original.directory, changed(original), second.current.revision))
        assertEquals("New body", path.readText())
    }

    @Test
    fun `an external edit during save preparation returns a conflict before publishing intent`() {
        val root = temporary.resolve("library")
        val original = createOriginal(FileSystemPromptTemplateRepository(root))
        val codec = TemplateMetadataCodec()
        val repository = FileSystemPromptTemplateRepository(root, codec, LinearPlaceholderParser(), TemplateFileStore(codec) {
            if (it == TemplateSaveStep.BEFORE_STAGE) original.directory.resolve("prompt.md").writeText("External change")
        })
        val conflict = assertIs<RepositoryResult.Conflict>(repository.update(original.directory, changed(original), original.revision))
        assertEquals("External change", conflict.current.template.markdown)
        assertFalse(original.directory.resolve(TemplateFileStore.JOURNAL_FILE).exists())
    }

    @Test
    fun `a journal before either canonical file is discoverable as one recoverable template`() {
        val root = temporary.resolve("library")
        val codec = TemplateMetadataCodec()
        val failing = FileSystemPromptTemplateRepository(root, codec, LinearPlaceholderParser(), TemplateFileStore(codec) {
            if (it == TemplateSaveStep.AFTER_STAGE) throw IOException("Interrupted create")
        })
        assertIs<RepositoryResult.Failure>(failing.create(PromptTemplateDraft(name = "Original", markdown = "New body")))
        val reopened = FileSystemPromptTemplateRepository(root)
        val summary = assertIs<LibraryEntry.Template>(reopened.scan().children.single()).summary
        assertEquals(TemplateHealth.HEALTHY, summary.health)
        assertEquals("New body", load(reopened, summary.directory).template.markdown)
    }

    @Test
    fun `invalid journal blocks reads and is retained without changing canonical files`() {
        val repository = FileSystemPromptTemplateRepository(temporary.resolve("library"))
        val original = createOriginal(repository)
        val journal = original.directory.resolve(TemplateFileStore.JOURNAL_FILE)
        journal.writeText("interrupted or invalid journal")
        assertIs<RepositoryResult.Failure>(repository.load(original.directory))
        assertEquals(TemplateHealth.BROKEN, assertIs<LibraryEntry.Template>(repository.scan().children.single()).summary.health)
        assertEquals("Old body", original.directory.resolve(FileSystemPromptTemplateRepository.MARKDOWN_FILE).readText())
        assertEquals("interrupted or invalid journal", journal.readText())
    }

    @Test
    fun `two JVM writers cannot interleave and the waiting stale writer conflicts`() {
        val root = temporary.resolve("library")
        val original = createOriginal(FileSystemPromptTemplateRepository(root))
        val waiting = startProcess(root, "wait", "unused")
        var holding: Process? = null
        try {
            awaitFile(root.resolve("waiting-ready"))
            holding = startProcess(root, "hold", "unused")
            awaitFile(root.resolve("holding-ready"))
            root.resolve("start-waiting").writeText("")
            awaitFile(root.resolve("waiting-attempt"))
            assertFalse(waiting.waitFor(300, TimeUnit.MILLISECONDS), "Writer must wait while the first process holds the lock")
            root.resolve("release-holder").writeText("")
            assertTrue(holding.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, holding.exitValue(), root.resolve("hold.log").readText())
            assertTrue(waiting.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, waiting.exitValue(), root.resolve("wait.log").readText())
            assertEquals("Conflict", root.resolve("wait-result").readText())
            assertEquals("New body", load(FileSystemPromptTemplateRepository(root), original.directory).template.markdown)
        } finally {
            holding?.destroyForcibly()
            waiting.destroyForcibly()
        }
    }

    private fun startProcess(root: Path, mode: String, step: String): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("test.runtime.classpath"),
        TemplateSaveProcess::class.java.name, root.toString(), mode, step,
    ).redirectErrorStream(true).redirectOutput(root.resolve("$mode.log").toFile()).start()

    private fun createOriginal(repository: FileSystemPromptTemplateRepository): StoredTemplate =
        assertIs<RepositoryResult.Success<StoredTemplate>>(repository.create(PromptTemplateDraft(name = "Original", markdown = "Old body"))).value

    private fun changed(original: StoredTemplate) = PromptTemplateDraft(original.template.id, "Changed", markdown = "New body")

    private fun load(repository: FileSystemPromptTemplateRepository, path: Path): StoredTemplate =
        assertIs<RepositoryResult.Success<StoredTemplate>>(repository.load(path)).value
}

/** Real separate JVMs distinguish process interruption and OS lock behaviour from exception unwinding. */
object TemplateSaveProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val mode = args[1]
        val original = assertIs<RepositoryResult.Success<StoredTemplate>>(
            FileSystemPromptTemplateRepository(root).load(root.resolve("original")),
        ).value
        if (mode == "wait") {
            root.resolve("waiting-ready").writeText("")
            awaitFile(root.resolve("start-waiting"))
            root.resolve("waiting-attempt").writeText("")
        }
        val codec = TemplateMetadataCodec()
        val repository = FileSystemPromptTemplateRepository(root, codec, LinearPlaceholderParser(), TemplateFileStore(codec) { step ->
            if (mode == "crash" && step.name == args[2]) Runtime.getRuntime().halt(23)
            if (mode == "hold" && step == TemplateSaveStep.AFTER_MARKDOWN) {
                root.resolve("holding-ready").writeText("")
                awaitFile(root.resolve("release-holder"))
            }
        })
        val result = repository.update(original.directory, PromptTemplateDraft(original.template.id, "Changed", markdown = "New body"), original.revision)
        root.resolve("$mode-result").writeText(when (result) {
            is RepositoryResult.Conflict -> "Conflict"
            is RepositoryResult.Success -> "Success"
            is RepositoryResult.Failure -> error(result.message)
        })
    }
}

private fun awaitFile(path: Path) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (!path.exists()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for $path" }
        Thread.sleep(20)
    }
}
