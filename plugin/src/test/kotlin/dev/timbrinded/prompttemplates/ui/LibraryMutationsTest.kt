package dev.timbrinded.prompttemplates.ui

import dev.timbrinded.prompttemplates.core.RepositoryResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class LibraryMutationsTest {
    @Test
    fun `unexpected repository exception becomes a failure and retains its cause`() {
        val exception = IllegalStateException("broken iterator")

        val result = runRepositoryOperationSafely<Unit> { throw exception }

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertEquals("Unexpected repository error: broken iterator", failure.message)
        assertSame(exception, failure.cause)
    }

    @Test
    fun `repository operation fail-safe does not hide cancellation`() {
        val cancellation = CancellationException("stop")

        val thrown = assertFailsWith<CancellationException> {
            runRepositoryOperationSafely<Unit> { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }
}
