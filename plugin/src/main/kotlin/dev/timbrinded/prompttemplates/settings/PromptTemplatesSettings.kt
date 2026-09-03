package dev.timbrinded.prompttemplates.settings

import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Property
import java.nio.file.Path

@Service(Service.Level.APP)
@State(
    name = "PromptTemplatesSettings",
    storages = [Storage("promptTemplates.xml", roamingType = RoamingType.DISABLED)],
)
class PromptTemplatesSettings :
    SerializablePersistentStateComponent<PromptTemplatesSettings.SettingsState>(SettingsState()) {
    data class SettingsState(
        @JvmField @field:Property val libraryPath: String = defaultLibraryPath(),
        @JvmField @field:Property val confirmDeletion: Boolean = true,
        @JvmField val pinnedTemplateIds: List<String> = emptyList(),
        @JvmField val recentTemplateIds: List<String> = emptyList(),
        @JvmField @field:Property val splitterProportion: Float = 0.28f,
    )

    var libraryPath: String
        get() = state.libraryPath
        set(value) {
            updateState { it.copy(libraryPath = value.trim()) }
        }

    var confirmDeletion: Boolean
        get() = state.confirmDeletion
        set(value) {
            updateState { it.copy(confirmDeletion = value) }
        }

    var splitterProportion: Float
        get() = state.splitterProportion
        set(value) {
            updateState { it.copy(splitterProportion = value) }
        }

    val libraryRoot: Path
        get() = Path.of(libraryPath.ifBlank { defaultLibraryPath() }).toAbsolutePath().normalize()

    fun markRecent(id: String) {
        updateState { current ->
            current.copy(
                recentTemplateIds = buildList {
                    add(id)
                    current.recentTemplateIds.asSequence()
                        .filterNot(id::equals)
                        .take(MAX_RECENTS - 1)
                        .forEach(::add)
                },
            )
        }
    }

    companion object {
        private const val MAX_RECENTS = 20

        fun getInstance(): PromptTemplatesSettings = service()

        private fun defaultLibraryPath(): String =
            Path.of(System.getProperty("user.home"), "Prompt Templates").toString()
    }
}
