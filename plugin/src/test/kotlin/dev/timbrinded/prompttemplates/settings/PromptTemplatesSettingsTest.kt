package dev.timbrinded.prompttemplates.settings

import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PromptTemplatesSettingsTest {
    @Test
    fun `usage stores only library-scoped identities with bounded deduplicated recents`() {
        val settings = PromptTemplatesSettings()
        val first = Path.of("/tmp/first")
        val second = Path.of("/tmp/second")
        settings.toggleFavourite("same-id", first)
        repeat(25) { settings.recordUse("id-$it", first) }
        settings.recordUse("id-20", first)
        settings.recordUse("same-id", second)

        assertEquals(listOf("same-id"), settings.usage(first).favourites)
        assertEquals(emptyList(), settings.usage(second).favourites)
        assertEquals(20, settings.usage(first).recents.size)
        assertEquals("id-20", settings.usage(first).recents.first())
        assertEquals(20, settings.usage(first).recents.toSet().size)
        assertEquals(listOf("same-id"), settings.usage(second).recents)
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), PromptTemplatesSettings.SettingsState::class.java)
        assertEquals(settings.state, restored)
        settings.toggleFavourite("same-id", first)
        assertEquals(emptyList(), settings.usage(first).favourites)
    }

    @Test
    fun `settings use copy-on-write updates and trim the library path`() {
        val settings = PromptTemplatesSettings()

        settings.libraryPath = "  /tmp/prompt-library  "
        settings.confirmDeletion = false

        assertEquals("/tmp/prompt-library", settings.state.libraryPath)
        assertFalse(settings.state.confirmDeletion)
    }

    @Test
    fun `immutable state keeps stable XML names and reads legacy beans`() {
        val state = PromptTemplatesSettings.SettingsState(
            libraryPath = "/tmp/library",
            confirmDeletion = false,
            pinnedTemplateIds = listOf("pinned"),
            recentTemplateIds = listOf("recent"),
            splitterProportion = 0.4f,
        )
        val serialized = XmlSerializer.serialize(state)
        val propertyNames = buildSet {
            serialized.attributes.mapTo(this) { it.name }
            serialized.children.mapNotNullTo(this) { it.getAttributeValue("name") }
        }
        val restored = XmlSerializer.deserialize(serialized, PromptTemplatesSettings.SettingsState::class.java)
        assertEquals(
            setOf(
                "libraryPath",
                "confirmDeletion",
                "pinnedTemplateIds",
                "recentTemplateIds",
                "libraryUsage",
                "splitterProportion",
            ),
            propertyNames,
        )
        assertEquals(state, restored)

        // Backward compat: XML written by the previous mutable bean still reads.
        val legacy = LegacySettingsState(
            libraryPath = "/tmp/legacy-library",
            confirmDeletion = false,
            pinnedTemplateIds = mutableListOf("pinned"),
            recentTemplateIds = mutableListOf("recent"),
            splitterProportion = 0.45f,
        )
        val legacyXml = XmlSerializer.serialize(legacy)
        assertEquals(
            PromptTemplatesSettings.SettingsState(
                libraryPath = "/tmp/legacy-library",
                confirmDeletion = false,
                pinnedTemplateIds = listOf("pinned"),
                recentTemplateIds = listOf("recent"),
                splitterProportion = 0.45f,
            ),
            XmlSerializer.deserialize(legacyXml, PromptTemplatesSettings.SettingsState::class.java),
        )
    }
}

private data class LegacySettingsState(
    var libraryPath: String,
    var confirmDeletion: Boolean,
    var pinnedTemplateIds: MutableList<String>,
    var recentTemplateIds: MutableList<String>,
    var splitterProportion: Float,
)
