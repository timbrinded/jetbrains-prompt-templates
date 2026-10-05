package dev.timbrinded.prompttemplates.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadGenerationTrackerTest {
    private val oldRoot = Path.of("/libraries/old")
    private val newRoot = Path.of("/libraries/new")

    @Test
    fun `detail loads track generations and invalidate only prior results`() {
        val generations = LoadGenerationTracker()
        val libraryGeneration = generations.beginLibraryLoad(oldRoot)
        val target = TemplateDetailTarget(Path.of("library", "prompt"), "template-id")

        // Detail activity alone does not invalidate the in-flight library scan.
        generations.beginDetailLoad(target, TemplateDetailIntent.USE)
        generations.invalidateDetailLoad()
        assertTrue(generations.acceptLibraryLoad(libraryGeneration, oldRoot))

        // A newer detail request invalidates only the prior detail result.
        val firstDetailRequest = generations.beginDetailLoad(target, TemplateDetailIntent.USE)
        val secondDetailRequest = generations.beginDetailLoad(target, TemplateDetailIntent.EDIT)
        assertTrue(generations.acceptLibraryLoad(libraryGeneration, oldRoot))
        assertFalse(generations.acceptDetailLoad(firstDetailRequest))
        assertTrue(generations.acceptDetailLoad(secondDetailRequest))
    }

    @Test
    fun `a scan of the previous library that lands last is rejected`() {
        val generations = LoadGenerationTracker()
        val oldScan = generations.beginLibraryLoad(oldRoot)
        val newScan = generations.beginLibraryLoad(newRoot)

        assertTrue(generations.acceptLibraryLoad(newScan, newRoot))
        assertFalse(generations.acceptLibraryLoad(oldScan, oldRoot))
        // Even a current generation is refused when it reports another library than the one it was started for.
        assertFalse(generations.acceptLibraryLoad(newScan, oldRoot))
    }

    @Test
    fun `requests made against the previous library root are recognised as stale`() {
        val generations = LoadGenerationTracker()
        assertFalse(generations.isCurrentLibraryRoot(oldRoot), "Nothing is current before the first scan")

        generations.beginLibraryLoad(oldRoot)
        assertTrue(generations.isCurrentLibraryRoot(oldRoot.resolve("reviews/..")))

        generations.beginLibraryLoad(newRoot)
        assertFalse(generations.isCurrentLibraryRoot(oldRoot))
        assertTrue(generations.isCurrentLibraryRoot(newRoot))
    }
}
