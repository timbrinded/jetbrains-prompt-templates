package dev.timbrinded.prompttemplates.e2e

import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.PathWalkOption.INCLUDE_DIRECTORIES
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.uuid.Uuid

class TestWorkspace private constructor(
    val root: Path,
    val userHome: Path,
    val project: Path,
    val library: Path,
    val evidence: Path,
) {
    val templates = TestLibrary(library)

    init {
        listOf(userHome, project, library, evidence).forEach(::requireInsideRoot)
    }

    fun writeLibraryManifest() {
        val destination = evidence.resolve("library-manifest.txt")
        requireInsideRoot(destination)
        destination.writeText(templates.manifest())
    }

    fun writePathRecord(starterPaths: Map<String, Path>) {
        val destination = evidence.resolve("isolated-paths.txt")
        requireInsideRoot(destination)
        val lines = buildList {
            add("workspace=$root")
            add("user.home=$userHome")
            add("project=$project")
            add("library=$library")
            starterPaths.toSortedMap().forEach { (name, path) -> add("starter.$name=$path") }
        }
        destination.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    fun requireInsideRoot(path: Path): Path {
        val normalRoot = root.toAbsolutePath().normalize()
        val normalPath = path.toAbsolutePath().normalize()
        require(normalPath.startsWith(normalRoot)) {
            "UI test path must stay below $normalRoot: $normalPath"
        }
        return normalPath
    }

    companion object {
        private const val OUTPUT_PROPERTY = "prompt.templates.ui.test.output"

        fun create(testName: String): TestWorkspace {
            val configuredOutput = System.getProperty(OUTPUT_PROPERTY)
                ?: error("Missing -D$OUTPUT_PROPERTY. Run this test with :plugin:integrationTest.")
            val outputRoot = Path.of(configuredOutput).toAbsolutePath().normalize()
            val safeName = testName.lowercase()
                .replace(UNSAFE_TEST_NAME_CHARACTERS, "-")
                .trim('-')
                .ifEmpty { "ui-test" }
            val root = outputRoot.resolve("$safeName-${Uuid.random()}").createDirectories()
            val userHome = root.resolve("home").createDirectories()
            val project = root.resolve("project").createDirectories()
            val library = userHome.resolve("Prompt Templates").createDirectories()
            val evidence = root.resolve("evidence").createDirectories()

            project.resolve("README.md").writeText("# Prompt Templates UI test project\n")

            return TestWorkspace(root, userHome, project, library, evidence)
        }

        private val UNSAFE_TEST_NAME_CHARACTERS = Regex("[^a-z0-9-]+")
    }
}

class TestLibrary(
    val root: Path,
) {
    fun createFolder(relativePath: String) {
        resolve(relativePath).createDirectories()
    }

    fun createTemplate(
        relativeDirectory: String,
        name: String,
        id: String,
    ): Path {
        require(Uuid.parseHexDashOrNull(id) != null) { "Template ID must be a UUID: $id" }
        val directory = resolve(relativeDirectory).createDirectories()
        directory.resolve("prompt.md").writeText("# $name\n\n{{objective}}\n")
        directory.resolve("prompt.meta.json").writeText(
            metadataJson(id = id, name = name),
        )
        return directory
    }

    fun manifest(): String {
        if (!root.exists(LinkOption.NOFOLLOW_LINKS)) return "<library does not exist>\n"
        return root.walk(INCLUDE_DIRECTORIES)
            .sortedBy { path -> root.relativize(path).pathString }
            .joinToString("\n", postfix = "\n", transform = ::manifestLine)
    }

    private fun resolve(relativePath: String): Path {
        val destination = root.resolve(relativePath).normalize()
        require(destination.startsWith(root.toAbsolutePath().normalize())) {
            "Library path escapes its root: $relativePath"
        }
        return destination
    }

    private fun manifestLine(path: Path): String {
        val relative = root.relativize(path).pathString.ifEmpty { "." }
        return when {
            path.isSymbolicLink() -> "L $relative -> ${path.readSymbolicLink()}"
            path.isDirectory(LinkOption.NOFOLLOW_LINKS) -> "D $relative"
            path.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> {
                val bytes = path.readBytes()
                "F $relative ${bytes.size} ${sha256(bytes)}"
            }
            else -> "O $relative"
        }
    }

    private fun metadataJson(id: String, name: String): String =
        """
        {
          "schemaVersion": 1,
          "id": "${jsonEscape(id)}",
          "name": "${jsonEscape(name)}",
          "description": "Created by the isolated IDE UI test harness.",
          "tags": ["e2e"],
          "variables": [
            {
              "key": "objective",
              "label": "Objective",
              "type": "multiline",
              "required": true,
              "options": []
            }
          ]
        }
        """.trimIndent() + "\n"

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHexString()
}
