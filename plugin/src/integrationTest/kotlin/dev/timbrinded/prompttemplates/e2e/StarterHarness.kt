package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitFor
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
import com.intellij.tools.ide.starter.product.webstorm.WebStorm
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class StarterHarness private constructor(
    val workspace: TestWorkspace,
) {
    fun run(block: Driver.(PromptTemplatesUi) -> Unit) {
        val context = createContext()
        StarterFailureReporter.ensureInstalled()
        try {
            runSession(context, block)
        } finally {
            workspace.writeLibraryManifest()
        }
    }

    private fun createContext(): IDETestContext = Starter.newContext(
        testName = workspace.root.name,
        testCase = TestCase(
            IdeInfo.WebStorm,
            LocalProjectInfo(workspace.project),
        ).withVersion(IDE_VERSION),
    ).apply {
        require(paths.testHome.any { it.toString() == "WS-$PINNED_IDE_BUILD" }) {
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
        context.runIdeWithDriver(runTimeout = 8.minutes)
            .useDriverAndCloseIde(closeIdeTimeout = 2.minutes) {
                val ui = PromptTemplatesUi(this)
                try {
                    waitForIndicators(5.minutes)
                    block(ui)
                } finally {
                    if (java.lang.Boolean.getBoolean("prompt.templates.explore")) {
                        val complete = workspace.evidence.resolve("exploration-complete")
                        workspace.evidence.resolve("exploration-ready").writeText(
                            "Explore this isolated IDE, then create $complete to finish.\n",
                        )
                        println("Exploratory IDE ready. Create $complete to finish.")
                        waitFor("exploratory testing is complete", 5.minutes) { complete.exists() }
                    }
                    captureEvidence(ui, "run-1")
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
            )
        }.onFailure { workspace.writeEvidenceFailure("$prefix-tree-state", it) }
        runCatching { captureSwingHierarchy(prefix) }
            .onFailure { workspace.writeEvidenceFailure("$prefix-swing-hierarchy", it) }
    }

    private fun Driver.captureScreenshot(prefix: String) {
        val target = workspace.evidence.resolve("$prefix-screenshot.png")
        val remoteScreenshot = takeScreenshot(prefix)?.let(Path::of)
        if (remoteScreenshot != null && remoteScreenshot.isRegularFile()) {
            remoteScreenshot.copyTo(target, overwrite = true)
            return
        }

        require(System.getenv("XDG_SESSION_TYPE") != "wayland") {
            "Driver screenshot capture is unavailable in this Wayland session; run the E2E task under Xvfb"
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
        )
    }

    private fun IDETestContext.seedStableUiSettings() {
        val options = paths.configDir.resolve("options")
        options.createDirectories()
        // Keep notifications available for assertions after their transient balloons disappear.
        options.resolve("notifications.xml").writeText(
            """<application>
              |  <component name="NotificationConfiguration">
              |    <notification groupId="Prompt Templates" shouldLog="true" />
              |  </component>
              |</application>
              |""".trimMargin(),
        )
        options.resolve("ui.lnf.xml").writeText(
            """<application>
              |  <component name="LafManager" autodetect="false">
              |    <laf class-name="com.intellij.ide.ui.laf.IntelliJLaf" themeId="ExperimentalLight" />
              |  </component>
              |</application>
              |""".trimMargin(),
        )
    }

    private fun pluginPath(): Path {
        val configured = System.getProperty(PLUGIN_PATH_PROPERTY)
            ?: error("Missing -D$PLUGIN_PATH_PROPERTY. Run this test with :plugin:integrationTest.")
        val path = Path.of(configured).toAbsolutePath().normalize()
        require(path.exists()) { "Built plugin distribution does not exist: $path" }
        return path
    }

    companion object {
        private const val IDE_VERSION = "2026.2"
        private const val PINNED_IDE_BUILD = "262.8665.259"
        private const val PLUGIN_PATH_PROPERTY = "path.to.build.plugin"

        fun create(testName: String): StarterHarness = StarterHarness(TestWorkspace.create(testName))
    }
}

private fun TestWorkspace.writeEvidenceFailure(kind: String, error: Throwable) {
    evidence.resolve("$kind-capture-error.txt").writeText(
        error.stackTraceToString(),
    )
}

private object StarterFailureReporter {
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
                        fail("$testName failed in the IDE process: $message\n$details")
                    }
                }
            }
        }
    }

    // Referencing the object runs its one-time DI registration above.
    fun ensureInstalled() = Unit
}
