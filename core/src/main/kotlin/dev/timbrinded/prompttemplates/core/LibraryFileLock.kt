package dev.timbrinded.prompttemplates.core

import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One JVM gate avoids overlapping Java file locks; the stable lock file also gates other IDE processes. */
internal object LibraryFileLock {
    const val FILE_NAME = ".prompt-templates.lock"
    private val gate = ReentrantLock()
    private val heldRoots = mutableSetOf<Path>() // Accessed only by the thread holding the reentrant gate.

    fun <T> withLock(root: Path, block: () -> T): T = gate.withLock {
        val realRoot = root.toRealPath()
        if (realRoot in heldRoots) return@withLock block()
        FileChannel.open(realRoot.resolve(FILE_NAME), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                heldRoots.add(realRoot)
                try {
                    block()
                } finally {
                    heldRoots.remove(realRoot)
                }
            }
        }
    }
}
