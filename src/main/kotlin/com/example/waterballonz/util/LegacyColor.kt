package com.example.waterballonz.util

/**
 * Converts legacy Minecraft colour codes (`&c`, `&l`, etc.) into MiniMessage
 * tags so that Adventure components can be created from a single source.
 *
 * This implementation handles:
 *  - Standard colour codes `&0..&9`, `&a..&f`.
 *  - Format codes `&k`, `&l`, `&m`, `&n`, `&o`, `&r`.
 *  - Hex colours `&#rrggbb` (single-string form).
 *
 * Any existing MiniMessage tags in the source string are preserved.
 */
object LegacyColor {

    private val colorMap = mapOf(
        '0' to "black",
        '1' to "dark_blue",
        '2' to "dark_green",
        '3' to "dark_aqua",
        '4' to "dark_red",
        '5' to "dark_purple",
        '6' to "gold",
        '7' to "gray",
        '8' to "dark_gray",
        '9' to "blue",
        'a' to "green",
        'b' to "aqua",
        'c' to "red",
        'd' to "light_purple",
        'e' to "yellow",
        'f' to "white",
    )

    private val formatMap = mapOf(
        'k' to "obfuscated",
        'l' to "bold",
        'm' to "strikethrough",
        'n' to "underlined",
        'o' to "italic",
        'r' to "reset",
    )

    fun toMiniMessage(input: String): String {
        if (input.isEmpty()) return input
        val out = StringBuilder(input.length + 32)
        var i = 0
        val chars = input.toCharArray()
        while (i < chars.size) {
            val c = chars[i]
            if (c == '&' && i + 1 < chars.size) {
                val code = chars[i + 1]
                when {
                    code == '#' -> {
                        // Hex colour &#rrggbb
                        if (i + 7 < chars.size) {
                            val hex = String(chars, i + 2, 6)
                            out.append("<color:#$hex>")
                            i += 8
                            continue
                        } else {
                            out.append('&')
                            i++
                            continue
                        }
                    }
                    colorMap.containsKey(code) -> {
                        out.append("<").append(colorMap[code]).append(">")
                        i += 2
                        continue
                    }
                    formatMap.containsKey(code) -> {
                        if (code == 'r') {
                            out.append("<reset>")
                        } else {
                            out.append("<").append(formatMap[code]).append(">")
                        }
                        i += 2
                        continue
                    }
                    else -> {
                        // Unknown code — leave literal
                        out.append(c)
                        i++
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
