package com.example.waterballonz.messages

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.ConfigManager
import com.example.waterballonz.util.LegacyColor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

/**
 * Provides message resolution for WaterBallonZ.
 *
 * Internally builds Adventure [Component] instances from messages.yml (so
 * MiniMessage and legacy `&` codes both work). When sending to players,
 * however, the component is serialized back to a legacy `§`-coloured
 * [String] and dispatched via the **string-based** Bukkit API methods
 * (`sendMessage(String)`, `sendActionBar(String)`, `sendTitle(...)`).
 *
 * Why? Because the Adventure `sendMessage(Component)` convenience overloads
 * on `CommandSender` / `Player` exist in Paper's API jar but are not
 * declared directly on the Bukkit `CommandSender` interface at runtime in
 * Spigot 1.21 — calling them throws `NoSuchMethodError`. The string-based
 * API is present on every Bukkit/Spigot version since 1.16, so the plugin
 * works identically on Paper and Spigot.
 *
 * Formatting syntaxes supported in messages.yml:
 *  - Legacy ampersand codes (`&c`, `&l`, etc.) — auto-converted.
 *  - MiniMessage tags (`<red>`, `<bold>`) — preserved.
 *
 * Placeholders use `{name}` tokens, e.g. `{player}`, `{arena}`.
 */
class MessageService(
    private val plugin: WaterBallonZPlugin,
    private val configManager: ConfigManager,
) {
    private val messages: MutableMap<String, String> = mutableMapOf()
    private val mini = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.legacySection()

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

    /** Serialize an Adventure Component into a legacy `§`-coloured string. */
    fun toLegacy(component: Component): String = legacySerializer.serialize(component)

    /** Resolve a key into a legacy `§`-coloured string (with prefix applied). */
    fun legacy(key: String, vararg placeholders: Pair<String, Any?>): String =
        toLegacy(get(key, *placeholders))

    /** Resolve a key into a legacy `§`-coloured string (without prefix). */
    fun legacyRaw(key: String, vararg placeholders: Pair<String, Any?>): String =
        toLegacy(getRaw(key, *placeholders))

    // ----------------------------------------------------------------
    // Senders — all use Bukkit's string-based API for cross-platform
    // compatibility (Spigot 1.21 + Paper 1.21+).
    // ----------------------------------------------------------------

    fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        sender.sendMessage(legacy(key, *placeholders))
    }

    fun sendActionBar(player: Player, key: String, vararg placeholders: Pair<String, Any?>) {
        // Player#sendActionBar(String) exists on both Spigot and Paper.
        player.sendActionBar(legacyRaw(key, *placeholders))
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
        // Player#sendTitle(String, String, int, int, int) exists on both
        // Spigot 1.21 and Paper 1.21+. Avoids the Adventure Title API,
        // which requires Paper-specific overloads on Player.
        val title = legacyRaw(titleKey, *placeholders)
        val subtitle = legacyRaw(subtitleKey, *placeholders)
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
    }

    private fun substitute(template: String, placeholders: Array<out Pair<String, Any?>>): String {
        var result = template
        for ((name, value) in placeholders) {
            result = result.replace("{$name}", value?.toString() ?: "")
        }
        return result
    }
}
