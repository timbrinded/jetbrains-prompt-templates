package dev.timbrinded.prompttemplates.ui

import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Path

internal enum class TemplateDetailIntent { USE, EDIT }

internal data class TemplateDetailTarget(
    val directory: Path,
    val templateId: String?,
)

internal data class TemplateDetailRequest(
    val generation: Int,
    val target: TemplateDetailTarget,
    val intent: TemplateDetailIntent,
)

/** Keeps background library scans independent from detail loads while rejecting stale results in each channel. */
internal class LoadGenerationTracker {
    private val library = AtomicInteger()
    private val detail = AtomicInteger()
    @Volatile
    private var pendingDetail: TemplateDetailRequest? = null

    fun beginLibraryLoad(): Int = library.incrementAndGet()

    fun isCurrentLibraryLoad(generation: Int): Boolean = generation == library.get()

    @Synchronized
    fun beginDetailLoad(target: TemplateDetailTarget, intent: TemplateDetailIntent): TemplateDetailRequest =
        TemplateDetailRequest(detail.incrementAndGet(), target, intent).also { pendingDetail = it }

    @Synchronized
    fun invalidateDetailLoad() {
        detail.incrementAndGet()
        pendingDetail = null
    }

    fun pendingDetailLoad(): TemplateDetailRequest? = pendingDetail

    @Synchronized
    fun acceptDetailLoad(request: TemplateDetailRequest): Boolean {
        if (request.generation != detail.get() || pendingDetail != request) return false
        pendingDetail = null
        return true
    }
}
