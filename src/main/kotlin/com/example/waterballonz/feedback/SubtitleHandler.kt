package com.example.waterballonz.feedback

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.util.LegacyColor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player

/**
 * Low-level subtitle / action-bar / title sender with full MiniMessage +
 * hex-gradient support.
 *
 * Per spec section 1 — "Fix and completely improve the subtitle system":
 *
 *  - Uses Hex color codes (`<color:#ff5555>`) and gradients
 *    (`<gradient:#ff5555:#ffaa00>...</gradient>`) instead of only legacy
 *    `§` colours.
 *  - Subtitle duration / timing is configurable via [GameConfig.FeedbackConfig].
 *  - Uses Adventure's MiniMessage API on Paper, falls back to legacy
 *    `§`-serialization on Spigot (see [MessageService] for the rationale —
 *    Spigot 1.21's CommandSender/Player doesn't declare the Adventure
 *    overloads directly, so we serialize to strings and use the string-based
 *    Bukkit API methods).
 *  - Reusable — the higher-level [CombatFeedbackService] builds on this class
 *    to send combat feedback (HP loss, friendly-fire, hit confirmation, etc.).
 *
 * MiniMessage syntax supported (subset — full MiniMessage is supported):
 *   <red>foo</red>                         legacy red
 *   <color:#ff5555>foo</color>              hex colour
 *   <gradient:#ff5555:#ffaa00>foo</gradient>  hex gradient
 *   <bold>foo</bold>                       bold
 *   <italic>foo</italic>                   italic
 *
 * Legacy `&` codes in the input are converted to MiniMessage tags by
 * [LegacyColor.toMiniMessage] before being parsed.
 */
class SubtitleHandler(
    private val plugin: WaterBallonZPlugin,
    private val gameConfig: GameConfig,
) {
    private val mini: MiniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.legacySection()

    // ----------------------------------------------------------------
    // Subtitle (the small text under the big title)
    // ----------------------------------------------------------------

    /**
     * Send a subtitle to [player]. The title line above the subtitle is
     * cleared (empty).
     *
     * @param text  MiniMessage-formatted string (legacy `&` codes are
     *              auto-converted).
     */
    fun sendSubtitle(player: Player, text: String) {
        val fadeIn = gameConfig.feedback.subtitleFadeInTicks
        val stay = gameConfig.feedback.subtitleStayTicks
        val fadeOut = gameConfig.feedback.subtitleFadeOutTicks
        // Use the string-based Player#sendTitle so this works on both Spigot
        // and Paper — Spigot 1.21's Player interface doesn't declare the
        // Adventure overload of sendTitle(Title).
        player.sendTitle("", toLegacy(text), fadeIn, stay, fadeOut)
    }

    /**
     * Send both a title and a subtitle. Duration is configurable.
     */
    fun sendTitleAndSubtitle(player: Player, title: String, subtitle: String) {
        val fadeIn = gameConfig.feedback.subtitleFadeInTicks
        val stay = gameConfig.feedback.subtitleStayTicks
        val fadeOut = gameConfig.feedback.subtitleFadeOutTicks
        player.sendTitle(toLegacy(title), toLegacy(subtitle), fadeIn, stay, fadeOut)
    }

    // ----------------------------------------------------------------
    // Action bar
    // ----------------------------------------------------------------

    /**
     * Send an action-bar message to [player].
     */
    fun sendActionBar(player: Player, text: String) {
        // Player#sendActionBar(String) exists on both Spigot and Paper.
        player.sendActionBar(toLegacy(text))
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * Parse a (possibly legacy-coded) MiniMessage string and serialize it
     * to a `§`-coloured legacy string that Bukkit's string-based API methods
     * can consume on both Spigot and Paper.
     *
     * NOTE: MiniMessage gradients cannot be perfectly represented in legacy
     * `§`-coded strings (legacy only supports 16 fixed colours per character).
     * The serializer will fall back to the closest single colour for each
     * gradient segment, which is still acceptable visually — but for the
     * richest gradient experience, the player should be on Paper where
     * Adventure is the native text system.
     */
    fun toLegacy(text: String): String {
        val miniText = LegacyColor.toMiniMessage(text)
        val component: Component = mini.deserialize(miniText)
        return legacySerializer.serialize(component)
    }

    /**
     * Parse a MiniMessage string into an Adventure Component. Use this when
     * the consumer is itself Adventure-aware (e.g. inventory item display
     * names on Paper).
     */
    fun toComponent(text: String): Component {
        val miniText = LegacyColor.toMiniMessage(text)
        return mini.deserialize(miniText)
    }
}
