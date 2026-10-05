package dev.timbrinded.prompttemplates.core

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.uuid.Uuid

/** Entry moves that never replace an existing entry. Callers hold the library lock. */
internal object LibraryMoves {
    fun moveWithoutReplacement(source: Path, destination: Path): Path {
        try {
            return Files.move(source, destination, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            return Files.move(source, destination)
        }
    }

    /**
     * Applies a new casing through a hidden working directory, because a case-insensitive filesystem treats
     * both names as one entry. A failed second step moves the folder back, or names the retained directory.
     */
    fun moveCaseOnly(source: Path, destination: Path) {
        val temporary = nextCaseRenameTemporaryPath(source.parent)
        moveWithoutReplacement(source, temporary)
        try {
            moveWithoutReplacement(temporary, destination)
        } catch (error: IOException) {
            throw rollbackCaseOnlyRename(temporary, source, error)
        } catch (error: SecurityException) {
            throw rollbackCaseOnlyRename(
                temporary,
                source,
                IOException("Permission was denied while applying the requested folder-name casing.", error),
            )
        }
    }

    private fun nextCaseRenameTemporaryPath(parent: Path): Path {
        while (true) {
            val candidate = parent.resolve("${LibraryLayout.RENAME_SCRATCH_PREFIX}${Uuid.random()}")
            if (!Files.exists(candidate, NOFOLLOW_LINKS)) return candidate
        }
    }

    private fun rollbackCaseOnlyRename(
        temporary: Path,
        source: Path,
        renameError: IOException,
    ): IOException {
        if (!Files.exists(temporary, NOFOLLOW_LINKS) || Files.exists(source, NOFOLLOW_LINKS)) {
            return IOException(
                "Unable to apply the requested folder-name casing. ${retainedScratchFolderHint(temporary)}",
                renameError,
            )
        }
        return try {
            moveWithoutReplacement(temporary, source)
            renameError
        } catch (rollbackError: IOException) {
            IOException(
                "Unable to apply the requested folder-name casing or restore the original name. " +
                    retainedScratchFolderHint(temporary),
                renameError,
            ).apply { addSuppressed(rollbackError) }
        } catch (rollbackError: SecurityException) {
            IOException(
                "Unable to apply the requested folder-name casing or restore the original name. " +
                    retainedScratchFolderHint(temporary),
                renameError,
            ).apply { addSuppressed(rollbackError) }
        }
    }

    private fun retainedScratchFolderHint(temporary: Path): String =
        "The folder remains at '$temporary'. It is hidden from the library; rename it in a file manager to restore it."
}
