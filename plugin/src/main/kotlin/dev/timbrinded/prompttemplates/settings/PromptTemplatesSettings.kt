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
        @JvmField val libraryUsage: List<LibraryUsage> = emptyList(),
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

    fun usage(root: Path = libraryRoot): LibraryUsage = state.libraryUsage
        .firstOrNull { it.libraryPath == root.toAbsolutePath().normalize().toString() }
        ?: LibraryUsage(libraryPath = root.toAbsolutePath().normalize().toString())

    fun toggleFavourite(id: String, root: Path = libraryRoot) = updateUsage(root) { current ->
        current.copy(favourites = if (id in current.favourites) current.favourites - id else current.favourites + id)
    }

    fun recordUse(id: String, root: Path = libraryRoot) = updateUsage(root) { current ->
        current.copy(recents = (listOf(id) + current.recents.filterNot { it == id }).take(20))
    }

    private fun updateUsage(root: Path, change: (LibraryUsage) -> LibraryUsage) {
        val path = root.toAbsolutePath().normalize().toString()
        updateState { state ->
            val current = state.libraryUsage.firstOrNull { it.libraryPath == path } ?: LibraryUsage(libraryPath = path)
            val updated = change(current)
            state.copy(libraryUsage = state.libraryUsage.filterNot { it.libraryPath == updated.libraryPath } + updated)
        }
    }

    data class LibraryUsage(
        @JvmField @field:Property val libraryPath: String = "",
        @JvmField val favourites: List<String> = emptyList(),
        @JvmField val recents: List<String> = emptyList(),
    )

    companion object {
        fun getInstance(): PromptTemplatesSettings = service()

        private fun defaultLibraryPath(): String =
            Path.of(System.getProperty("user.home"), "Prompt Templates").toString()
    }
}
