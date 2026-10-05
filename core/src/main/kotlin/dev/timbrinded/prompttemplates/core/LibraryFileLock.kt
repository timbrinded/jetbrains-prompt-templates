package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.FileChannel
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
 * While another process holds the lock, a waiting thread polls without holding the gate, so this IDE's other
 * operations can still try. A [withLock] timeout bounds that wait: changes fail with an error instead of blocking
 * behind a frozen IDE, while reads pass `null` and wait, keeping the last state they showed.
 */
internal object LibraryFileLock {
    private val CHANGE_TIMEOUT = 10.seconds
    private val POLL_INTERVAL = 50.milliseconds
    private val gate = ReentrantLock()
    private val heldRoots = mutableSetOf<Path>() // Accessed only by the thread holding the reentrant gate.

    fun <T> withLock(root: Path, timeout: Duration? = CHANGE_TIMEOUT, block: () -> T): T {
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }
        while (true) {
            gate.lock()
            try {
                val realRoot = root.toRealPath()
                if (realRoot in heldRoots) return block()
                FileChannel.open(realRoot.resolve(LibraryLayout.LOCK_FILE), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                    channel.tryLock()?.use {
                        heldRoots.add(realRoot)
                        try {
                            return block()
                        } finally {
                            heldRoots.remove(realRoot)
                        }
                    }
                }
                // Waiting inside another library's lock would keep the gate and stall every local change.
                check(gate.holdCount == 1) { "A nested library lock on a different root cannot wait for another IDE." }
            } finally {
                gate.unlock()
            }
            if (deadline?.hasPassedNow() == true) {
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
