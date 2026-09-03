package dev.timbrinded.prompttemplates.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptTemplatesWorkspaceStateTest {
    @Test
    fun `workspace state round-trips XML and replaces copy-on-write`() {
        val state = PromptTemplatesWorkspaceState.WorkspaceState(
            expandedFolderPaths = listOf("Reviews", "Reviews/Security"),
            selectedTemplateId = "selected",
        )
        val serialized = XmlSerializer.serialize(state)
        val propertyNames = buildSet {
            serialized.attributes.mapTo(this) { it.name }
            serialized.children.mapNotNullTo(this) { it.getAttributeValue("name") }
        }
        assertEquals(setOf("expandedFolderPaths", "selectedTemplateId"), propertyNames)
        assertEquals(
            state,
            XmlSerializer.deserialize(serialized, PromptTemplatesWorkspaceState.WorkspaceState::class.java),
        )

        val workspace = PromptTemplatesWorkspaceState()
        workspace.replaceExpandedFolderPaths(listOf("Reviews"))
        workspace.selectedTemplateId = "template"
        assertEquals(listOf("Reviews"), workspace.expandedFolderPaths)
        assertEquals("template", workspace.selectedTemplateId)
        workspace.selectedTemplateId = null
        assertEquals(null, workspace.state.selectedTemplateId)
        assertEquals(listOf("Reviews"), workspace.state.expandedFolderPaths)
    }
}
