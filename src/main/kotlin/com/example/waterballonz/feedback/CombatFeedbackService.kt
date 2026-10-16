package com.example.waterballonz.feedback

import com.example.waterballonz.combat.service.DamageService
import com.example.waterballonz.combat.service.KnockbackService
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.messages.MessageService
import com.example.waterballonz.team.Team
import org.bukkit.entity.Player

/**
 * High-level combat feedback coordinator.
 *
 * Builds on [SubtitleHandler] to produce polished combat feedback for:
 *
 *  - **Enemy hit on victim**: short "−5% HP" subtitle with a red→orange
 *    hex gradient.
 *  - **Enemy hit confirmation for shooter**: short action-bar "Hit: PlayerName
 *    (combo ×N)" in green/teal.
 *  - **Friendly-fire warning**: "Friendly Fire — cannot damage PlayerName"
 *    subtitle in blue/purple.
 *  - **Elimination**: full-screen title "ELIMINATED" with red gradient.
 *
 * The styling uses MiniMessage hex + gradient tags so it works on Paper
 * natively, and falls back to the nearest single colour on Spigot (where
 * Adventure runtime isn't bundled with the server jar).
 *
 * Per spec section 1: "Use different colors for: Damage dealt, Friendly-fire
 * prevention, Thrower information, Victim information, Team information,
 * Combat feedback."
 *
 * Per spec section 11: "-2.0 HP with an attractive red/orange gradient."
 *
 * Per spec section 22: "Avoid spammy subtitles."
 */
class CombatFeedbackService(
    private val plugin: com.example.waterballonz.WaterBallonZPlugin,
    private val gameConfig: GameConfig,
    private val subtitleHandler: SubtitleHandler,
    private val messageService: MessageService,
) {

    /**
     * Show feedback for a successful enemy hit.
     *
     * @param shooter       The thrower.
     * @param victim        The player who was hit.
     * @param damage        Damage dealt (in custom HP %).
     * @param victimHpAfter Victim's resulting HP after the hit.
     * @param combo         Shooter's current combo count (after the hit).
     */
    fun showEnemyHit(
        shooter: Player,
        victim: Player,
        damage: Int,
        victimHpAfter: Int,
        combo: Int,
    ) {
        // 1. Victim feedback: red→orange gradient subtitle showing HP lost.
        if (gameConfig.feedback.showVictimFeedback) {
            val subtitle = buildEnemyHitVictimSubtitle(damage, victimHpAfter)
            subtitleHandler.sendSubtitle(victim, subtitle)
        }

        // 2. Shooter feedback: green action bar with hit confirmation.
        if (gameConfig.feedback.showAttackerFeedback) {
            val actionBar = buildEnemyHitShooterActionBar(victim.name, damage, combo)
            subtitleHandler.sendActionBar(shooter, actionBar)
        }
    }

    /**
     * Show feedback when a friendly-fire hit was rejected.
     *
     * @param shooter      The thrower.
     * @param victim       The teammate who got hit but took no damage.
     * @param shooterTeam  The team both players are on.
     */
    fun showFriendlyFire(shooter: Player, victim: Player, shooterTeam: Team) {
        if (!gameConfig.feedback.showFriendlyFireFeedback) return

        // Shooter gets a clear "you hit a teammate" message.
        val shooterMsg = buildFriendlyFireShooterMessage(victim.name, shooterTeam)
        subtitleHandler.sendActionBar(shooter, shooterMsg)

        // Victim gets a subtle "your teammate hit you" message — only if
        // they don't already have something more important on their screen.
        val victimSubtitle = buildFriendlyFireVictimSubtitle(shooter.name, shooterTeam)
        subtitleHandler.sendSubtitle(victim, victimSubtitle)
    }

    /**
     * Show the elimination title (full screen) when [victim]'s HP has hit 0.
     */
    fun showElimination(victim: Player) {
        val title = "<gradient:#ff0000:#aa0000><bold>ELIMINATED</bold></gradient>"
        val subtitle = "<color:#aaaaaa>You will be sent to the spectator area.</color>"
        subtitleHandler.sendTitleAndSubtitle(victim, title, subtitle)
    }

    // ----------------------------------------------------------------
    // Styling helpers — kept private so the API surface stays small.
    // ----------------------------------------------------------------

    private fun buildEnemyHitVictimSubtitle(damage: Int, hpAfter: Int): String {
        // Red→orange gradient: −5% HP in big letters.
        return "<gradient:#ff5555:#ffaa00><bold>-$damage% HP</bold></gradient> " +
               "<color:#aaaaaa>($hpAfter% left)</color>"
    }

    private fun buildEnemyHitShooterActionBar(victimName: String, damage: Int, combo: Int): String {
        // Green→teal for positive feedback. Combo indicator in gold.
        val comboTag = if (combo >= 2) " <color:#ffaa00><bold>×$combo</bold></color>" else ""
        return "<gradient:#55ff55:#00aaaa><bold>HIT</bold></gradient> " +
               "<color:#ffffff>$victimName</color> " +
               "<color:#aaaaaa>(-$damage%)</color>$comboTag"
    }

    private fun buildFriendlyFireShooterMessage(victimName: String, team: Team): String {
        // Blue→purple gradient for friendly-fire warning.
        val teamColor = team.miniMessageColor  // "red" / "blue"
        return "<gradient:#5555ff:#aa55ff><bold>FRIENDLY FIRE</bold></gradient> " +
               "<$teamColor>$victimName</$teamColor> <color:#aaaaaa>is on your team</color>"
    }

    private fun buildFriendlyFireVictimSubtitle(shooterName: String, team: Team): String {
        val teamColor = team.miniMessageColor
        return "<color:#aaaaff><italic>Your teammate</italic></color> " +
               "<$teamColor>$shooterName</$teamColor> " +
               "<color:#aaaaff><italic>hit you — no damage</italic></color>"
    }
}
