package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One JVM gate avoids overlapping Java file locks; the stable lock file also gates other IDE processes.
 * Waiting is bounded, so a frozen IDE that holds the lock produces an error instead of blocking forever.
 */
internal object LibraryFileLock {
    const val FILE_NAME = ".prompt-templates.lock"
    private val DEFAULT_TIMEOUT = 10.seconds
    private val POLL_INTERVAL = 50.milliseconds
    private val gate = ReentrantLock()
    private val heldRoots = mutableSetOf<Path>() // Accessed only by the thread holding the reentrant gate.

    fun <T> withLock(root: Path, timeout: Duration = DEFAULT_TIMEOUT, block: () -> T): T {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        if (!interruptibly { gate.tryLock(timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS) }) {
            throw IOException("Another template library operation in this IDE is still running. Try again shortly.")
        }
        try {
            val realRoot = root.toRealPath()
            if (realRoot in heldRoots) return block()
            FileChannel.open(realRoot.resolve(FILE_NAME), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                acquire(channel, deadline).use {
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
                throw IOException(
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
