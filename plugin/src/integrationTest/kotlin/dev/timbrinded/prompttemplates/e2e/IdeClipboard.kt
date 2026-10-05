package dev.timbrinded.prompttemplates.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.StringSelectionRef
import java.awt.datatransfer.DataFlavor

@Remote("com.intellij.openapi.ide.CopyPasteManager")
internal interface IdeClipboard {
    fun getInstance(): IdeClipboard
    fun getContents(flavor: DataFlavor): String?
    fun setContents(content: StringSelectionRef)
}

/**
 * Seeds clipboard text the way an IDE copy does. The driver's `copyToClipboard` sets the AWT clipboard directly,
 * so reading it back makes the IDE query its own X selection, which times out while the UI is busy.
 */
internal fun Driver.copyThroughIde(text: String) =
    utility(IdeClipboard::class).getInstance().setContents(new(StringSelectionRef::class, text))
