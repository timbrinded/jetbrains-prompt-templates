package dev.timbrinded.prompttemplates.e2e

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.pathString
import kotlin.io.path.writeText

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
        destination.writeText(templates.manifest(), StandardCharsets.UTF_8)
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
        destination.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
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
                .replace(Regex("[^a-z0-9-]+"), "-")
                .trim('-')
                .ifEmpty { "ui-test" }
            val root = outputRoot.resolve("$safeName-${UUID.randomUUID()}").createDirectories()
            val userHome = root.resolve("home").createDirectories()
            val project = root.resolve("project").createDirectories()
            val library = userHome.resolve("Prompt Templates").createDirectories()
            val evidence = root.resolve("evidence").createDirectories()

            project.resolve("README.md").writeText(
                "# Prompt Templates UI test project\n",
                StandardCharsets.UTF_8,
            )

            return TestWorkspace(root, userHome, project, library, evidence)
        }
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
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Template ID must be a UUID: $id" }
        val directory = resolve(relativeDirectory).createDirectories()
        directory.resolve("prompt.md").writeText("# $name\n\n{{objective}}\n", StandardCharsets.UTF_8)
        directory.resolve("prompt.meta.json").writeText(
            metadataJson(id = id, name = name),
            StandardCharsets.UTF_8,
        )
        return directory
    }

    fun writeOrder(
        relativeFolder: String,
        folders: List<String>,
        templates: List<String>,
    ) {
        val folder = resolve(relativeFolder).createDirectories()
        val destination = folder.resolve(".prompt-templates-order.json")
        destination.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"schemaVersion\": 1,")
                appendLine("  \"folders\": ${jsonStringArray(folders)},")
                appendLine("  \"templates\": ${jsonStringArray(templates)}")
                appendLine("}")
            },
            StandardCharsets.UTF_8,
        )
    }

    fun manifest(): String {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return "<library does not exist>\n"
        return Files.walk(root).use { paths ->
            paths
                .sorted(compareBy { root.relativize(it).pathString })
                .map(::manifestLine)
                .toList()
                .joinToString("\n", postfix = "\n")
        }
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
            Files.isSymbolicLink(path) -> "L $relative -> ${Files.readSymbolicLink(path)}"
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "D $relative"
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                val bytes = Files.readAllBytes(path)
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

    private fun jsonStringArray(values: List<String>): String = values.joinToString(
        prefix = "[",
        postfix = "]",
    ) { value -> "\"${jsonEscape(value)}\"" }

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
        .joinToString("") { byte -> "%02x".format(byte) }
}
