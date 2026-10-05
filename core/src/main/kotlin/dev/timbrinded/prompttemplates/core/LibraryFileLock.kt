package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Another IDE process held the library lock past the wait deadline. The library itself is unchanged. */
class LibraryLockedException(message: String) : IOException(message)

/**
 * One JVM gate avoids overlapping Java file locks; the stable lock file also gates other IDE processes.
 * Waiting for another process is bounded, so a frozen IDE that holds the lock produces an error instead of
 * blocking forever. Operations in this IDE queue on the gate as before.
 */
internal object LibraryFileLock {
    private val DEFAULT_TIMEOUT = 10.seconds
    private val POLL_INTERVAL = 50.milliseconds
    private val gate = ReentrantLock()
    private val heldRoots = mutableSetOf<Path>() // Accessed only by the thread holding the reentrant gate.

    fun <T> withLock(root: Path, timeout: Duration = DEFAULT_TIMEOUT, block: () -> T): T {
        gate.lock()
        try {
            val realRoot = root.toRealPath()
            if (realRoot in heldRoots) return block()
            FileChannel.open(realRoot.resolve(LibraryLayout.LOCK_FILE), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                acquire(channel, TimeSource.Monotonic.markNow() + timeout).use {
                    heldRoots.add(realRoot)
                    try {
                        return block()
                    } finally {
                        heldRoots.remove(realRoot)
                    }
                }
            }
        } finally {
            gate.unlock()
        }
    }

    private fun acquire(channel: FileChannel, deadline: TimeSource.Monotonic.ValueTimeMark): FileLock {
        while (true) {
            channel.tryLock()?.let { return it }
            if (deadline.hasPassedNow()) {
                throw LibraryLockedException(
                    "The template library is locked by another IDE process. Try again when it finishes, " +
                        "or close that IDE if it is not responding.",
                )
            }
            interruptibly { Thread.sleep(POLL_INTERVAL.inWholeMilliseconds) }
        }
    }

    private inline fun <T> interruptibly(block: () -> T): T = try {
        block()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Interrupted while waiting for the template library lock.").apply { initCause(error) }
    }
}
