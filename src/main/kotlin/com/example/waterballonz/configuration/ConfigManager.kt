package com.example.waterballonz.configuration

import com.example.waterballonz.WaterBallonZPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Loads and owns the YAML configuration files for WaterBallonZ.
 *
 * Three resource files are managed:
 *  - `config.yml` — gameplay & integration settings.
 *  - `messages.yml` — player-facing text.
 *  - `arenas.yml` — arena definitions (managed by ArenaRepository).
 *
 * Each file is created from the bundled defaults on first run. The user's
 * values are preserved across plugin upgrades.
 */
class ConfigManager(private val plugin: WaterBallonZPlugin) {

    lateinit var gameConfig: GameConfig
        private set

    val dataFolder: File get() = plugin.dataFolder

    init {
        if (!dataFolder.exists()) dataFolder.mkdirs()
    }

    fun loadAll() {
        copyDefaults()
        reload()
    }

    fun reload() {
        copyDefaults()
        gameConfig = loadGameConfig()
    }

    private fun copyDefaults() {
        listOf("config.yml", "messages.yml", "arenas.yml").forEach { name ->
            val file = File(dataFolder, name)
            if (!file.exists()) {
                plugin.getResource(name)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun loadGameConfig(): GameConfig {
        val file = File(dataFolder, "config.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)

        val general = GameConfig.GeneralConfig(
            prefix = yaml.getString("general.prefix", "&8[&6WaterBallonZ&8] &7") ?: "&8[&6WaterBallonZ&8] &7",
            debug = yaml.getBoolean("general.debug", false),
            autoStart = yaml.getBoolean("general.auto-start", true),
            autoBalanceTeams = yaml.getBoolean("general.auto-balance-teams", true),
            restorePlayerState = yaml.getBoolean("general.restore-player-state", true),
            returnToLobbyAfterMatch = yaml.getBoolean("general.return-to-lobby-after-match", true),
            lobby = GameConfig.LobbyConfig(
                enabled = yaml.getBoolean("general.lobby.enabled", true),
                fallbackToSpawn = yaml.getBoolean("general.lobby.fallback-to-spawn", true),
                returnToPreGameLocation = yaml.getBoolean("general.lobby.return-to-pre-game-location", true),
            ),
        )

        val game = GameConfig.GameRulesConfig(
            minimumPlayers = yaml.getInt("game.minimum-players", 2).coerceAtLeast(2),
            maximumPlayers = yaml.getInt("game.maximum-players", 16).coerceIn(2, 200),
            countdownSeconds = yaml.getInt("game.countdown-seconds", 3).coerceIn(1, 60),
            maximumDurationSeconds = yaml.getInt("game.maximum-duration-seconds", 600).coerceIn(30, 3600),
            endDelaySeconds = yaml.getInt("game.end-delay-seconds", 5).coerceIn(0, 60),
            requireSpectatorLocation = yaml.getBoolean("game.require-spectator-location", false),
        )

        val health = GameConfig.HealthConfig(
            maximumPercent = yaml.getInt("health.maximum-percent", 100).coerceIn(1, 1000),
            startingPercent = yaml.getInt("health.starting-percent", 100).coerceIn(1, 1000),
            regenerationEnabled = yaml.getBoolean("health.regeneration-enabled", true),
            regenerationIntervalSeconds = yaml.getInt("health.regeneration-interval-seconds", 120).coerceAtLeast(1),
            regenerationAmountPercent = yaml.getInt("health.regeneration-amount-percent", 5).coerceAtLeast(0),
        )

        val waterball = GameConfig.WaterBallConfig(
            startingAmount = yaml.getInt("waterball.starting-amount", 5).coerceAtLeast(0),
            additionalPerMinute = yaml.getInt("waterball.additional-per-minute", 1).coerceAtLeast(0),
            maximumHeld = yaml.getInt("waterball.maximum-held", 64).coerceIn(1, 64 * 36),
            itemMaterial = yaml.getString("waterball.item-material", "SNOWBALL") ?: "SNOWBALL",
            displayName = yaml.getString("waterball.display-name", "&6&lWater Ball &r&7(Click-Right)")
                ?: "&6&lWater Ball &r&7(Click-Right)",
            lore = yaml.getStringList("waterball.lore"),
            customModelData = yaml.getInt("waterball.custom-model-data", 0),
            enchantedGlint = yaml.getBoolean("waterball.enchanted-glint", false),
        )

        val combat = GameConfig.CombatConfig(
            baseDamagePercent = yaml.getInt("combat.base-damage-percent", 5).coerceIn(1, 100),
            maximumDamagePercent = yaml.getInt("combat.maximum-damage-percent", 15).coerceIn(1, 100),
            damagePerComboStep = yaml.getInt("combat.damage-per-combo-step", 5).coerceIn(1, 100),
            comboWindowSeconds = yaml.getInt("combat.combo-window-seconds", 3).coerceAtLeast(1),
            friendlyFire = yaml.getBoolean("combat.friendly-fire", false),
        )

        val effects = GameConfig.EffectsConfig(
            countdownSoundEnabled = yaml.getBoolean("effects.countdown-sound-enabled", true),
            startSoundEnabled = yaml.getBoolean("effects.start-sound-enabled", true),
            splashSoundEnabled = yaml.getBoolean("effects.splash-sound-enabled", true),
            waterParticlesEnabled = yaml.getBoolean("effects.water-particles-enabled", true),
            particleCount = yaml.getInt("effects.particle-count", 20).coerceIn(0, 500),
            refillSoundEnabled = yaml.getBoolean("effects.refill-sound-enabled", true),
        )

        val hud = GameConfig.HudConfig(
            healthActionbarEnabled = yaml.getBoolean("hud.health-actionbar-enabled", true),
            healthRefreshTicks = yaml.getInt("hud.health-refresh-ticks", 10).coerceAtLeast(1),
            comboIndicatorEnabled = yaml.getBoolean("hud.combo-indicator-enabled", true),
            scoreboardEnabled = yaml.getBoolean("hud.scoreboard-enabled", false),
        )

        val disconnect = GameConfig.DisconnectConfig(
            eliminateOnDisconnect = yaml.getBoolean("disconnect.eliminate-on-disconnect", true),
            reconnectEnabled = yaml.getBoolean("disconnect.reconnect-enabled", false),
            reconnectGraceSeconds = yaml.getInt("disconnect.reconnect-grace-seconds", 30).coerceAtLeast(0),
        )

        val statistics = GameConfig.StatisticsConfig(
            enabled = yaml.getBoolean("statistics.enabled", true),
            backend = yaml.getString("statistics.backend", "SQLITE") ?: "SQLITE",
        )

        val integrations = GameConfig.IntegrationsConfig(
            placeholderapiEnabled = yaml.getBoolean("integrations.placeholderapi-enabled", true),
        )

        val snowball = GameConfig.SnowballConfig(
            velocity = yaml.getDouble("snowball.velocity", 3.0),
            damageMobs = yaml.getBoolean("snowball.damage-mobs", false),
            mobDamage = yaml.getDouble("snowball.mob-damage", 2.0),
            tracker = GameConfig.SnowballConfig.TrackerConfig(
                armorStandEnabled = yaml.getBoolean("snowball.tracker.armor-stand-enabled", false),
            ),
            knockback = GameConfig.SnowballConfig.KnockbackConfig(
                horizontal = yaml.getDouble("snowball.knockback.horizontal", 0.35),
                vertical = yaml.getDouble("snowball.knockback.vertical", 0.15),
            ),
        )

        val feedback = GameConfig.FeedbackConfig(
            subtitleFadeInTicks = yaml.getInt("feedback.subtitle.fade-in-ticks", 2),
            subtitleStayTicks = yaml.getInt("feedback.subtitle.stay-ticks", 30),
            subtitleFadeOutTicks = yaml.getInt("feedback.subtitle.fade-out-ticks", 5),
            showAttackerFeedback = yaml.getBoolean("feedback.show-attacker-feedback", true),
            showVictimFeedback = yaml.getBoolean("feedback.show-victim-feedback", true),
            showFriendlyFireFeedback = yaml.getBoolean("feedback.show-friendly-fire-feedback", true),
            useHexGradients = yaml.getBoolean("feedback.use-hex-gradients", true),
        )

        return GameConfig(
            general = general,
            game = game,
            health = health,
            waterball = waterball,
            combat = combat,
            effects = effects,
            hud = hud,
            disconnect = disconnect,
            statistics = statistics,
            integrations = integrations,
            snowball = snowball,
            feedback = feedback,
        )
    }
}
