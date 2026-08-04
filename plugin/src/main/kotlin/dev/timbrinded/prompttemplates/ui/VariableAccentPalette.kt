package dev.timbrinded.prompttemplates.ui

import com.intellij.ui.JBColor
import dev.timbrinded.prompttemplates.core.PromptVariable
import java.awt.Color

internal data class VariableAccent(
    val foreground: Color,
    val background: Color,
)

internal object VariableAccentPalette {
    private val accents = listOf(
        accent(0x2458A6, 0x82AAFF, 0xE7F0FF, 0x26364D),
        accent(0x9A4A00, 0xF2A65A, 0xFFF0E0, 0x493321),
        accent(0x2E6B3A, 0x78C98A, 0xE8F5EA, 0x263D2B),
        accent(0x6842A8, 0xC3A0F7, 0xF1E9FC, 0x392D4A),
        accent(0x9A376A, 0xEE8BBB, 0xFBE8F1, 0x482C3B),
        accent(0x176B6B, 0x6FC7C7, 0xE3F4F3, 0x243E3D),
    )

    fun forVariables(variables: List<PromptVariable>): Map<String, VariableAccent> =
        variables.mapIndexed { index, variable -> variable.key to accents[index % accents.size] }.toMap()

    private fun accent(
        lightForeground: Int,
        darkForeground: Int,
        lightBackground: Int,
        darkBackground: Int,
    ) = VariableAccent(
        foreground = JBColor(Color(lightForeground), Color(darkForeground)),
        background = JBColor(Color(lightBackground), Color(darkBackground)),
    )
}
