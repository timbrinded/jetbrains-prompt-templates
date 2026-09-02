package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class StarterHarness private constructor(
    val workspace: TestWorkspace,
) {
    private var runNumber = 0

    fun run(block: Driver.(PromptTemplatesUi) -> Unit) {
        runSessions(listOf(block))
    }

    fun runWithRestart(
        beforeRestart: Driver.(PromptTemplatesUi) -> Unit,
        afterRestart: Driver.(PromptTemplatesUi) -> Unit,
    ) {
        runSessions(listOf(beforeRestart, afterRestart))
    }

    private fun runSessions(blocks: List<Driver.(PromptTemplatesUi) -> Unit>) {
        val context = createContext()
        StarterFailureReporter.install(workspace, PINNED_IDE_BUILD)
        try {
            blocks.forEach { block -> runSession(context, block) }
        } finally {
            workspace.writeLibraryManifest()
        }
    }

    private fun createContext(): IDETestContext = Starter.newContext(
        testName = workspace.root.fileName.toString(),
        testCase = TestCase(
                IdeInfo.IdeaUltimate,
                LocalProjectInfo(workspace.project),
            ).withVersion(IDE_VERSION),
    ).apply {
        require(paths.testHome.any { it.toString() == "IU-$PINNED_IDE_BUILD" }) {
            "IDE $IDE_VERSION did not resolve to the pinned build $PINNED_IDE_BUILD: ${paths.testHome}"
        }
        PluginConfigurator(this).installPluginFromPath(pluginPath())
        seedStableUiSettings()
        applyVMOptionsPatch {
            withXmx(2_048)
            addSystemProperty("user.home", workspace.userHome.toString())
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("ide.show.tips.on.startup.default.value", false)
            addSystemProperty("idea.suppress.statistics.report", true)
        }
        workspace.writePathRecord(
            mapOf(
                "testHome" to paths.testHome,
                "config" to paths.configDir,
                "system" to paths.systemDir,
                "plugins" to paths.pluginsDir,
            ),
        )
    }

    private fun runSession(
        context: IDETestContext,
        block: Driver.(PromptTemplatesUi) -> Unit,
    ) {
        runNumber += 1
        context.runIdeWithDriver(runTimeout = 8.minutes)
            .useDriverAndCloseIde(closeIdeTimeout = 2.minutes) {
                val ui = PromptTemplatesUi(this)
                try {
                    waitForIndicators(5.minutes)
                    block(ui)
                } finally {
                    captureEvidence(ui, "run-$runNumber")
                }
            }
    }

    private fun Driver.captureEvidence(ui: PromptTemplatesUi, prefix: String) {
        runCatching { captureScreenshot(prefix) }
            .onFailure { workspace.writeEvidenceFailure("$prefix-screenshot", it) }
        runCatching {
            workspace.evidence.resolve("$prefix-tree-state.txt").writeText(
                buildString {
                    appendLine("expanded:")
                    ui.expandedPaths().forEach { appendLine("  $it") }
                    appendLine("selected:")
                    ui.selectedPaths().forEach { appendLine("  $it") }
                },
                StandardCharsets.UTF_8,
            )
        }.onFailure { workspace.writeEvidenceFailure("$prefix-tree-state", it) }
        runCatching { captureSwingHierarchy(prefix) }
            .onFailure { workspace.writeEvidenceFailure("$prefix-swing-hierarchy", it) }
    }

    private fun Driver.captureScreenshot(prefix: String) {
        val target = workspace.evidence.resolve("$prefix-screenshot.png")
        val remoteScreenshot = takeScreenshot(prefix)?.let(Path::of)
        if (remoteScreenshot != null && Files.isRegularFile(remoteScreenshot)) {
            Files.copy(remoteScreenshot, target, StandardCopyOption.REPLACE_EXISTING)
            return
        }

        require(System.getenv("XDG_SESSION_TYPE") != "wayland") {
            "Driver screenshot capture is unavailable in this Wayland session; CI uses Xvfb"
        }
        val screen = Toolkit.getDefaultToolkit().screenSize
        val image = Robot().createScreenCapture(Rectangle(screen))
        require(ImageIO.write(image, "png", target.toFile())) {
            "No PNG writer is available for $target"
        }
    }

    private fun Driver.captureSwingHierarchy(prefix: String) {
        val hierarchy = service(SwingHierarchyService::class)
            .getSwingHierarchyAsDOM(component = null, onlyFrontend = false)
        workspace.evidence.resolve("$prefix-swing-hierarchy.xml").writeText(
            hierarchy,
            StandardCharsets.UTF_8,
        )
    }

    private fun IDETestContext.seedStableUiSettings() {
        val options = paths.configDir.resolve("options")
        Files.createDirectories(options)
        options.resolve("ui.lnf.xml").writeText(
            """<application>
              |  <component name="LafManager" autodetect="false">
              |    <laf class-name="com.intellij.ide.ui.laf.IntelliJLaf" themeId="ExperimentalLight" />
              |  </component>
              |</application>
              |""".trimMargin(),
            StandardCharsets.UTF_8,
        )
    }

    private fun pluginPath(): Path {
        val configured = System.getProperty(PLUGIN_PATH_PROPERTY)
            ?: error("Missing -D$PLUGIN_PATH_PROPERTY. Run this test with :plugin:integrationTest.")
        val path = Path.of(configured).toAbsolutePath().normalize()
        require(Files.exists(path)) { "Built plugin distribution does not exist: $path" }
        return path
    }

    companion object {
        private const val IDE_VERSION = "2026.2.0.1"
        private const val PINNED_IDE_BUILD = "262.8665.337"
        private const val PLUGIN_PATH_PROPERTY = "path.to.build.plugin"

        fun create(testName: String): StarterHarness = StarterHarness(TestWorkspace.create(testName))
    }
}

private fun TestWorkspace.writeEvidenceFailure(kind: String, error: Throwable) {
    evidence.resolve("$kind-capture-error.txt").writeText(
        error.stackTraceToString(),
        StandardCharsets.UTF_8,
    )
}

private object StarterFailureReporter {
    @Volatile
    private var workspace: TestWorkspace? = null

    @Volatile
    private var ideBuild: String? = null

    init {
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                        kind: SyntheticTestKind,
                        generifyTestName: Boolean,
                    ) {
                        if (isKnownPinnedPlatformError(testName, details)) {
                            recordKnownPlatformError(testName, message, details)
                            return
                        }
                        fail("$testName failed in the IDE process: $message\n$details")
                    }
                }
            }
        }
    }

    fun install(workspace: TestWorkspace, ideBuild: String) {
        this.workspace = workspace
        this.ideBuild = ideBuild
    }

    private fun isKnownPinnedPlatformError(testName: String, details: String): Boolean =
        isKnownPinnedPlatformThemeError(testName, details) ||
            isKnownPinnedBundledTslintError(testName, details)

    private fun isKnownPinnedPlatformThemeError(testName: String, details: String): Boolean =
        ideBuild == AFFECTED_IDE_BUILD &&
            testName == KNOWN_ERROR &&
            details.contains(KNOWN_PLATFORM_FRAME) &&
            containsNoPromptTemplatesFrame(details)

    private fun isKnownPinnedBundledTslintError(testName: String, details: String): Boolean =
        ideBuild == AFFECTED_IDE_BUILD &&
            testName == KNOWN_TSLINT_ERROR &&
            details.contains(KNOWN_TSLINT_PLUGIN_EXCEPTION) &&
            details.contains(KNOWN_TSLINT_FRAME) &&
            containsNoPromptTemplatesFrame(details)

    private fun containsNoPromptTemplatesFrame(details: String): Boolean =
        !details.substringBefore("\tat org.junit.jupiter.api.AssertionUtils").contains(PLUGIN_PACKAGE)

    private fun recordKnownPlatformError(testName: String, message: String, details: String) {
        val evidence = workspace?.evidence ?: return
        Files.writeString(
            evidence.resolve("known-platform-errors.txt"),
            buildString {
                appendLine("Muted pinned JetBrains platform error")
                appendLine("IDE build: $ideBuild")
                appendLine("Exception: $testName")
                appendLine("Reporter message: $message")
                appendLine(details)
                appendLine()
            },
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private const val AFFECTED_IDE_BUILD = "262.8665.337"
    private const val KNOWN_ERROR =
        "java.lang.Throwable: Theme Islands Dark refers to unknown color scheme Islands Dark"
    private const val KNOWN_PLATFORM_FRAME =
        "\tat com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl.getSchemeForCurrentUITheme(EditorColorsManagerImpl.kt:266)"
    private const val KNOWN_TSLINT_ERROR =
        "java.lang.ClassNotFoundException: com.intellij.lang.javascript.linter.tslint.editor.TsLintCodeStyleEditorNotificationProvider"
    private const val KNOWN_TSLINT_PLUGIN_EXCEPTION =
        "com.intellij.diagnostic.PluginException: Cannot create extension (class=com.intellij.lang.javascript.linter.tslint.editor.TsLintCodeStyleEditorNotificationProvider) [Plugin: tslint]"
    private const val KNOWN_TSLINT_FRAME =
        "\tat com.intellij.ui.EditorNotificationsImpl\$updateEditors\$job\$1.invokeSuspend(EditorNotificationsImpl.kt:275)"
    private const val PLUGIN_PACKAGE = "dev.timbrinded.prompttemplates"
}
