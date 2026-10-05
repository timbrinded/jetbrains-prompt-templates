package dev.timbrinded.prompttemplates.core

import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.OS
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Windows creates symbolic links only with Developer Mode or elevation, so a test that needs one skips without it. */
internal fun createSymbolicLinkOrSkip(link: Path, target: Path): Path = try {
    Files.createSymbolicLink(link, target)
} catch (error: FileSystemException) {
    if (OS.current() != OS.WINDOWS) throw error
    abort("Symbolic links are unavailable: ${error.message}")
}

internal fun assumePosixPermissions() {
    assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "POSIX permissions are unavailable.")
}
