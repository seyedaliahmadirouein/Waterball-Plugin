package com.example.waterballonz.messages

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.ConfigManager
import com.example.waterballonz.util.LegacyColor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

/**
 * Provides Adventure `Component` messages resolved from `messages.yml`.
 *
 * Supports two formatting syntaxes:
 *  - Legacy ampersand codes (`&c`, `&l`, etc.) — auto-converted.
 *  - MiniMessage tags (`<red>`, `<bold>`) — preserved.
 *
 * Placeholders are replaced via simple `{name}` tokens.
 */
class MessageService(
    private val plugin: WaterBallonZPlugin,
    private val configManager: ConfigManager,
) {
    private val messages: MutableMap<String, String> = mutableMapOf()
    private val mini = MiniMessage.miniMessage()

    fun reload() {
        val file = File(plugin.dataFolder, "messages.yml")
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false)
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        messages.clear()
        yaml.getKeys(true).forEach { key ->
            val value = yaml.getString(key) ?: ""
            if (value.isNotEmpty()) messages[key] = value
        }
    }

    /** Resolve a message key into a raw (placeholder-substituted) string. */
    fun raw(key: String, vararg placeholders: Pair<String, Any?>): String {
        val template = messages[key] ?: key
        return substitute(template, placeholders)
    }

    /** Resolve a message key into an Adventure component (with prefix applied). */
    fun get(key: String, vararg placeholders: Pair<String, Any?>): Component {
        val text = raw(key, *placeholders)
        val prefixed = if (key.startsWith("prefix.") || key.startsWith("raw.") || key == "prefix" || key == "none") {
            text
        } else {
            val prefix = messages["prefix"] ?: ""
            if (prefix.isEmpty()) text else "$prefix$text"
        }
        return toComponent(prefixed)
    }

    /** Resolve a message key without the standard prefix. */
    fun getRaw(key: String, vararg placeholders: Pair<String, Any?>): Component =
        toComponent(raw(key, *placeholders))

    /** Convert a legacy-formatted string into an Adventure Component. */
    fun toComponent(text: String): Component {
        // Convert legacy & codes into MiniMessage tags, then parse.
        val miniText = LegacyColor.toMiniMessage(text)
        return mini.deserialize(miniText)
    }

    fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        sender.sendMessage(get(key, *placeholders))
    }

    fun sendActionBar(player: Player, key: String, vararg placeholders: Pair<String, Any?>) {
        player.sendActionBar(getRaw(key, *placeholders))
    }

    fun sendTitle(
        player: Player,
        titleKey: String,
        subtitleKey: String,
        fadeIn: Int = 10,
        stay: Int = 40,
        fadeOut: Int = 10,
        vararg placeholders: Pair<String, Any?>,
    ) {
        val title = getRaw(titleKey, *placeholders)
        val subtitle = getRaw(subtitleKey, *placeholders)
        player.showTitle(net.kyori.adventure.title.Title.title(
            title,
            subtitle,
            net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(fadeIn * 50L),
                java.time.Duration.ofMillis(stay * 50L),
                java.time.Duration.ofMillis(fadeOut * 50L),
            )
        ))
    }

    private fun substitute(template: String, placeholders: Array<out Pair<String, Any?>>): String {
        var result = template
        for ((name, value) in placeholders) {
            result = result.replace("{$name}", value?.toString() ?: "")
        }
        return result
    }
}
