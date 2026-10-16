package com.example.waterballonz.configuration

import com.example.waterballonz.team.Team

/**
 * Immutable snapshot of all game configuration values.
 *
 * The values are loaded once at startup and on `/wbz reload`. A running match
 * keeps the snapshot it was started with — reloads never mutate an active
 * match's combat rules.
 */
data class GameConfig(
    val general: GeneralConfig,
    val game: GameRulesConfig,
    val health: HealthConfig,
    val waterball: WaterBallConfig,
    val combat: CombatConfig,
    val effects: EffectsConfig,
    val hud: HudConfig,
    val disconnect: DisconnectConfig,
    val statistics: StatisticsConfig,
    val integrations: IntegrationsConfig,
    val snowball: SnowballConfig,
    val feedback: FeedbackConfig,
) {
    val lobby: LobbyConfig get() = general.lobby

    data class GeneralConfig(
        val prefix: String,
        val debug: Boolean,
        val autoStart: Boolean,
        val autoBalanceTeams: Boolean,
        val restorePlayerState: Boolean,
        val returnToLobbyAfterMatch: Boolean,
        val lobby: LobbyConfig,
    )

    data class LobbyConfig(
        val enabled: Boolean,
        val fallbackToSpawn: Boolean,
        val returnToPreGameLocation: Boolean,
    )

    data class GameRulesConfig(
        val minimumPlayers: Int,
        val maximumPlayers: Int,
        val countdownSeconds: Int,
        val maximumDurationSeconds: Int,
        val endDelaySeconds: Int,
        val requireSpectatorLocation: Boolean,
    )

    data class HealthConfig(
        val maximumPercent: Int,
        val startingPercent: Int,
        val regenerationEnabled: Boolean,
        val regenerationIntervalSeconds: Int,
        val regenerationAmountPercent: Int,
    )

    data class WaterBallConfig(
        val startingAmount: Int,
        val additionalPerMinute: Int,
        val maximumHeld: Int,
        val itemMaterial: String,
        val displayName: String,
        val lore: List<String>,
        val customModelData: Int,
        val enchantedGlint: Boolean,
    )

    data class CombatConfig(
        val baseDamagePercent: Int,
        val maximumDamagePercent: Int,
        val damagePerComboStep: Int,
        val comboWindowSeconds: Int,
        val friendlyFire: Boolean,
    ) {
        val comboWindowMillis: Long get() = comboWindowSeconds.toLong() * 1000L
    }

    data class EffectsConfig(
        val countdownSoundEnabled: Boolean,
        val startSoundEnabled: Boolean,
        val splashSoundEnabled: Boolean,
        val waterParticlesEnabled: Boolean,
        val particleCount: Int,
        val refillSoundEnabled: Boolean,
    )

    data class HudConfig(
        val healthActionbarEnabled: Boolean,
        val healthRefreshTicks: Int,
        val comboIndicatorEnabled: Boolean,
        val scoreboardEnabled: Boolean,
    )

    data class DisconnectConfig(
        val eliminateOnDisconnect: Boolean,
        val reconnectEnabled: Boolean,
        val reconnectGraceSeconds: Int,
    )

    data class StatisticsConfig(
        val enabled: Boolean,
        val backend: String,
    )

    data class IntegrationsConfig(
        val placeholderapiEnabled: Boolean,
    )

    /**
     * Snowball projectile + tracker configuration.
     *
     * Per spec sections 4, 6, 9, 10: damage, knockback, tracker,
     * and cleanup tuning all live here.
     */
    data class SnowballConfig(
        val velocity: Double,                  // blocks per second
        val damageMobs: Boolean,               // apply vanilla damage to mobs?
        val mobDamage: Double,                  // vanilla damage amount for mobs
        val tracker: TrackerConfig,
        val knockback: KnockbackConfig,
    ) {
        data class TrackerConfig(
            val armorStandEnabled: Boolean,      // spawn invisible ArmorStand follower?
        )
        data class KnockbackConfig(
            val horizontal: Double,              // horizontal push strength
            val vertical: Double,                // vertical pop strength
        )
    }

    /**
     * Combat feedback / subtitle configuration.
     *
     * Per spec sections 1, 11: hex gradients, configurable timing,
     * separate toggles for attacker / victim / friendly-fire feedback.
     */
    data class FeedbackConfig(
        val subtitleFadeInTicks: Int,
        val subtitleStayTicks: Int,
        val subtitleFadeOutTicks: Int,
        val showAttackerFeedback: Boolean,
        val showVictimFeedback: Boolean,
        val showFriendlyFireFeedback: Boolean,
        val useHexGradients: Boolean,
    )
}

/** Default colours for the two teams, exposed for convenience. */
object TeamDefaults {
    val red = Team.RED
    val blue = Team.BLUE
}
