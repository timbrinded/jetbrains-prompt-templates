package dev.timbrinded.prompttemplates.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PromptTemplatesSettingsTest {
    @Test
    fun `settings use copy-on-write updates and trim the library path`() {
        val settings = PromptTemplatesSettings()

        settings.libraryPath = "  /tmp/prompt-library  "
        settings.confirmDeletion = false

        assertEquals("/tmp/prompt-library", settings.state.libraryPath)
        assertFalse(settings.state.confirmDeletion)
    }

    @Test
    fun `recent templates remain unique and bounded`() {
        val settings = PromptTemplatesSettings()

        (1..25).forEach { settings.markRecent("template-$it") }
        settings.markRecent("template-20")

        assertEquals(20, settings.state.recentTemplateIds.size)
        assertEquals("template-20", settings.state.recentTemplateIds.first())
        assertEquals(1, settings.state.recentTemplateIds.count("template-20"::equals))
    }

    @Test
    fun `immutable state keeps the existing XML property names`() {
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
                "splitterProportion",
            ),
            propertyNames,
        )
        assertEquals(state, restored)
    }

    @Test
    fun `immutable state reads XML written by the previous mutable bean`() {
        val legacy = LegacySettingsState(
            libraryPath = "/tmp/legacy-library",
            confirmDeletion = false,
            pinnedTemplateIds = mutableListOf("pinned"),
            recentTemplateIds = mutableListOf("recent"),
            splitterProportion = 0.45f,
            expandedFolderPaths = mutableListOf("Reviews"),
            selectedTemplateId = "selected",
        )

        val legacyXml = XmlSerializer.serialize(legacy)
        val restored = XmlSerializer.deserialize(legacyXml, PromptTemplatesSettings.SettingsState::class.java)

        assertEquals(
            PromptTemplatesSettings.SettingsState(
                libraryPath = "/tmp/legacy-library",
                confirmDeletion = false,
                pinnedTemplateIds = listOf("pinned"),
                recentTemplateIds = listOf("recent"),
                splitterProportion = 0.45f,
            ),
            restored,
        )
    }
}

private data class LegacySettingsState(
    var libraryPath: String,
    var confirmDeletion: Boolean,
    var pinnedTemplateIds: MutableList<String>,
    var recentTemplateIds: MutableList<String>,
    var splitterProportion: Float,
    /** Tree state that development builds wrote into the application-level file; it now lives per project. */
    var expandedFolderPaths: MutableList<String>,
    var selectedTemplateId: String?,
)
