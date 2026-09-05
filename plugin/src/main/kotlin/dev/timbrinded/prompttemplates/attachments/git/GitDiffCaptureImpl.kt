package dev.timbrinded.prompttemplates.attachments.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.timbrinded.prompttemplates.attachments.GitDiffCapture
import dev.timbrinded.prompttemplates.attachments.GitDiffRepository
import dev.timbrinded.prompttemplates.attachments.GitDiffScope
import dev.timbrinded.prompttemplates.core.ContextAttachment
import dev.timbrinded.prompttemplates.core.MAX_ATTACHMENT_BYTES
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import java.time.Instant
import java.util.concurrent.TimeUnit

/** This service is loaded only when Git4Idea is enabled. No user-supplied commands or arguments. */
internal class GitDiffCaptureImpl(private val project: Project) : GitDiffCapture {
    override fun repositories(): List<GitDiffRepository> = GitRepositoryManager.getInstance(project).repositories
        .sortedBy { it.root.path }.map { GitDiffRepository(it.root.path, it.currentRevision ?: "no HEAD commit") }

    override fun capture(repository: GitDiffRepository, scope: GitDiffScope): ContextAttachment {
        val root = GitRepositoryManager.getInstance(project).repositories.firstOrNull { it.root.path == repository.root }?.root
            ?: throw IllegalArgumentException("The selected repository is unavailable: ${repository.root}")
        val head = read(root, GitCommand.REV_PARSE, listOf("--verify", "HEAD"), 128).trim()
        require(head.matches(Regex("[0-9a-f]{40,64}"))) { "Git diff capture requires a repository with a HEAD commit." }
        // Diff must not start repository-configured clean/process filters or fsmonitor helpers.
        val filterKeys = read(root, GitCommand.CONFIG,
            listOf("--name-only", "--get-regexp", "^filter\\..*\\.(clean|process)$"), 32 * 1024,
            acceptedExitCodes = intArrayOf(0, 1)).lineSequence().filter(String::isNotBlank).toList()
        require(filterKeys.all { it.startsWith("filter.") && (it.endsWith(".clean") || it.endsWith(".process")) }) {
            "The repository has unsupported filter configuration. No diff was captured."
        }
        val filterOverrides = filterKeys.map { it.substringBeforeLast('.') }.distinct().flatMap {
            listOf("$it.clean=", "$it.process=", "$it.required=false")
        }
        val arguments = buildList {
            addAll(listOf("--no-ext-diff", "--no-textconv", "--no-color", "--no-renames", "--ignore-submodules=none", "--submodule=short", "--src-prefix=a/", "--dst-prefix=b/"))
            if (scope == GitDiffScope.STAGED) addAll(listOf("--cached", head))
            add("--")
        }
        val text = read(root, GitCommand.DIFF, arguments, MAX_ATTACHMENT_BYTES, filterOverrides)
        require(read(root, GitCommand.REV_PARSE, listOf("--verify", "HEAD"), 128).trim() == head) {
            "The repository HEAD changed during capture. Try again."
        }
        require(text.isNotBlank()) { "The selected ${scope.label} scope has no tracked changes." }
        require(text.lineSequence().none { it.startsWith("Binary files ") || it == "GIT binary patch" || it.startsWith("+Subproject commit ") || it.startsWith("-Subproject commit ") }) {
            "This diff includes binary or submodule changes. Capture text files separately; no diff was attached."
        }
        require('\uFFFD' !in text && '\u0000' !in text) { "This diff is not supported UTF-8 text. No diff was attached." }
        val base = if (scope == GitDiffScope.STAGED) "base HEAD $head" else "base index; repository HEAD $head"
        return ContextAttachment("git:${root.path}:${scope.name}", "Git ${scope.label}",
            "${root.path} — $base — tracked on-disk changes; unsaved buffers and untracked files excluded",
            Instant.now().toString(), text)
    }

    private fun read(root: VirtualFile, command: GitCommand, arguments: List<String>, limit: Int,
        config: List<String> = emptyList(), acceptedExitCodes: IntArray = intArrayOf(0),
    ): String {
        val handler = CapturedGitHandler(project, root, command, limit).apply {
            addParameters(arguments)
            addConfigParameters(listOf("core.quotePath=false", "core.fsmonitor=false") + config)
            addCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS", "0")
            setSilent(true)
            setStdoutSuppressed(true)
            setStderrSuppressed(true)
        }
        val result = Git.getInstance().runCommandWithoutCollectingOutput(handler)
        require(!handler.tooLarge) { "The Git output exceeds the capture limit. No content was truncated or attached." }
        require(!handler.timedOut) { "Git capture exceeded 30 seconds. No diff was attached." }
        require(result.success(*acceptedExitCodes)) { "Git capture failed in ${root.path}. Check the configured Git executable and repository state." }
        return handler.text
    }
}

/** Collect raw chunks, bypassing GitLineHandler's content logger and unbounded line collector. */
private class CapturedGitHandler(project: Project, root: VirtualFile, command: GitCommand, private val limit: Int) :
    GitLineHandler(project, root, command) {
    private val output = StringBuilder()
    private var bytes = 0
    @Volatile var tooLarge = false
        private set
    @Volatile var timedOut = false
        private set
    val text: String get() = output.toString()

    override fun createProcess(commandLine: GeneralCommandLine): OSProcessHandler {
        val handler = OSProcessHandler(commandLine.withCharset(Charsets.UTF_8))
        val timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (!handler.isProcessTerminated) { timedOut = true; handler.destroyProcess() }
        }, 30, TimeUnit.SECONDS)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (!ProcessOutputType.isStdout(outputType) || tooLarge) return
                val chunk = event.text
                bytes += chunk.toByteArray(Charsets.UTF_8).size
                if (bytes > limit) { tooLarge = true; handler.destroyProcess() }
                else output.append(chunk)
            }
            override fun processTerminated(event: ProcessEvent) { timeout.cancel(false) }
        })
        return handler
    }
}
