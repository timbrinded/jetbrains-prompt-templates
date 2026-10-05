package dev.timbrinded.prompttemplates.core

import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.uuid.Uuid

/**
 * Replaces [target] with UTF-8 [text] through a new staging file beside it. The staging file gets the process
 * umask, or the replaced file's POSIX permissions, and reaches the device before the move. When the filesystem
 * cannot move atomically, [allowNonAtomicMove] chooses between a plain replacing move and failing.
 */
internal fun writeTextAtomically(target: Path, text: String, allowNonAtomicMove: Boolean) {
    val directory = requireNotNull(target.toAbsolutePath().parent) { "A destination parent is required." }
    val staging = directory.resolve("${TemplateFileStore.STAGE_PREFIX}${Uuid.random()}.tmp")
    try {
        FileChannel.open(staging, CREATE_NEW, WRITE).use { channel ->
            copyPosixPermissions(from = target, to = staging)
            val bytes = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try {
            Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            if (!allowNonAtomicMove) throw error
            Files.move(staging, target, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(staging)
    }
}

private fun copyPosixPermissions(from: Path, to: Path) {
    if (!Files.isRegularFile(from)) return
    val view = Files.getFileAttributeView(to, PosixFileAttributeView::class.java) ?: return
    view.setPermissions(Files.getPosixFilePermissions(from))
}
