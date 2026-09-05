package dev.timbrinded.prompttemplates.attachments

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.timbrinded.prompttemplates.core.ContextAttachment
import dev.timbrinded.prompttemplates.core.MAX_ATTACHMENT_BYTES
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface AttachmentSource {
    data class File(val url: String) : AttachmentSource
    data class GitDiff(val root: String, val scope: GitDiffScope) : AttachmentSource
}

internal enum class GitDiffScope(val label: String) {
    STAGED("Staged: HEAD → index"),
    UNSTAGED("Unstaged: index → working tree");
    override fun toString(): String = label
}

internal data class GitDiffRepository(val root: String, val head: String) {
    override fun toString(): String = "Repository ${java.nio.file.Path.of(root).fileName} — $root — HEAD $head"
}

/** Registered only by the optional Git descriptor. The baseline has no Git class references. */
internal interface GitDiffCapture {
    fun repositories(): List<GitDiffRepository>
    fun capture(repository: GitDiffRepository, scope: GitDiffScope): ContextAttachment
}

internal data class CapturedAttachment(val content: ContextAttachment, val source: AttachmentSource) {
    override fun toString(): String = "${if (source is AttachmentSource.File) "File" else "Diff"}: ${content.title} — ${content.byteCount} bytes — ${content.source}"
}

internal suspend fun captureAttachment(project: Project, source: AttachmentSource): CapturedAttachment = when (source) {
    is AttachmentSource.File -> {
        require(source.url.startsWith("file://")) { "Only local files can be attached. Remote and virtual-only sources are unsupported." }
        val file = readAction { VirtualFileManager.getInstance().findFileByUrl(source.url) }
            ?: throw IllegalArgumentException("The selected file no longer exists: ${source.url}")
        try {
            CapturedAttachment(captureFile(file), source)
        } catch (failure: IOException) {
            throw IllegalArgumentException("Cannot read '${file.path}': unsupported or unreadable text. No items were changed.", failure)
        }
    }
    is AttachmentSource.GitDiff -> withContext(Dispatchers.IO) {
        val provider = project.getService(GitDiffCapture::class.java)
            ?: throw IllegalArgumentException("Git diff capture requires the Git plugin. File attachments remain available.")
        val repository = provider.repositories().firstOrNull { it.root == source.root }
            ?: throw IllegalArgumentException("The selected Git repository is no longer available: ${source.root}")
        CapturedAttachment(provider.capture(repository, source.scope), source)
    }
}

private suspend fun captureFile(file: VirtualFile): ContextAttachment {
    if (file.fileSystem.protocol == "file") withContext(Dispatchers.IO) {
        require(Files.isRegularFile(file.toNioPath())) { "The source file is missing or is not a regular file: ${file.path}" }
    }
    val loaded = readAction {
        require(file.isValid && !file.isDirectory) { "Select an available file: ${file.path}" }
        require(!file.fileType.isBinary) { "Binary files cannot be attached: ${file.path}" }
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(file)?.let { document ->
            require(document.textLength <= MAX_ATTACHMENT_BYTES) { "The file exceeds the 256 KiB attachment limit: ${file.path}" }
            val state = if (documents.isDocumentUnsaved(document)) "unsaved" else "saved"
            fileAttachment(file, "editor buffer ($state)", document.text)
        }
    }
    if (loaded != null) return loaded
    return withContext(Dispatchers.IO) {
        val stamp = file.modificationStamp
        val localPath = file.takeIf { it.fileSystem.protocol == "file" }?.toNioPath()
        val before = localPath?.let { Files.readAttributes(it, BasicFileAttributes::class.java) }
        require(file.length <= MAX_ATTACHMENT_BYTES * 4L) { "The encoded source exceeds the 1 MiB read limit: ${file.path}" }
        val bytes = file.inputStream.use { it.readNBytes(MAX_ATTACHMENT_BYTES * 4 + 1) }
        require(bytes.size <= MAX_ATTACHMENT_BYTES * 4) { "The encoded source exceeds the 1 MiB read limit: ${file.path}" }
        val bom = file.bom?.size ?: 0
        val decoder = file.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes, bom.coerceAtMost(bytes.size), (bytes.size - bom).coerceAtLeast(0))).toString()
        val after = localPath?.let { Files.readAttributes(it, BasicFileAttributes::class.java) }
        require(file.isValid && file.modificationStamp == stamp && before?.lastModifiedTime() == after?.lastModifiedTime() && before?.size() == after?.size()) { "The file changed during capture. Try again: ${file.path}" }
        fileAttachment(file, "on-disk text", text)
    }
}

private fun fileAttachment(file: VirtualFile, state: String, text: String): ContextAttachment {
    require('\u0000' !in text) { "Binary content cannot be attached: ${file.path}" }
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_ATTACHMENT_BYTES) { "The file exceeds the 256 KiB attachment limit: ${file.path}" }
    return ContextAttachment("file:${file.url}", file.name, "${file.path} — $state", Instant.now().toString(), text)
}
