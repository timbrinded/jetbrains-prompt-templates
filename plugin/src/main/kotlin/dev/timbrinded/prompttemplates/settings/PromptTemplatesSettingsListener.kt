package dev.timbrinded.prompttemplates.settings

import com.intellij.util.messages.Topic
import java.nio.file.Path

fun interface PromptTemplatesSettingsListener {
    fun libraryRootChanged(root: Path)

    companion object {
        @JvmField
        val TOPIC: Topic<PromptTemplatesSettingsListener> = Topic.create(
            "Prompt template library settings changed",
            PromptTemplatesSettingsListener::class.java,
        )
    }
}
