package dev.timbrinded.prompttemplates.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path

@Service(Service.Level.APP)
@State(name = "PromptTemplatesSettings", storages = [Storage("promptTemplates.xml")])
class PromptTemplatesSettings : PersistentStateComponent<PromptTemplatesSettings.SettingsState> {
    data class SettingsState(
        var libraryPath: String = defaultLibraryPath(),
        var confirmDeletion: Boolean = true,
        var pinnedTemplateIds: MutableList<String> = mutableListOf(),
        var recentTemplateIds: MutableList<String> = mutableListOf(),
        var splitterProportion: Float = 0.28f,
    )

    private var settingsState = SettingsState()

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    val libraryRoot: Path
        get() = Path.of(settingsState.libraryPath.ifBlank { defaultLibraryPath() }).toAbsolutePath().normalize()

    fun markRecent(id: String) {
        settingsState.recentTemplateIds.remove(id)
        settingsState.recentTemplateIds.add(0, id)
        while (settingsState.recentTemplateIds.size > MAX_RECENTS) {
            settingsState.recentTemplateIds.removeLast()
        }
    }

    companion object {
        private const val MAX_RECENTS = 20

        fun getInstance(): PromptTemplatesSettings =
            ApplicationManager.getApplication().getService(PromptTemplatesSettings::class.java)

        private fun defaultLibraryPath(): String =
            Path.of(System.getProperty("user.home"), "Prompt Templates").toString()
    }
}
