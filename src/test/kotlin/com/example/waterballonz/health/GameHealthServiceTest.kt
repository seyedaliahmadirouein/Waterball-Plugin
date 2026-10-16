package com.example.waterballonz.health

import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.team.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the custom HP system.
 *
 * Tests cover:
 *  - HP starts at 100%
 *  - 5 damage → 95% HP
 *  - 15 damage on 10% HP → 0% (clamped)
 *  - HP never falls below 0 or above maximum
 *  - Regeneration caps at the maximum
 *  - Eliminated players cannot be healed back
 *  - Regeneration can be disabled via config
 */
class GameHealthServiceTest {

    private val defaultConfig = GameConfig(
        general = GameConfig.GeneralConfig(
            prefix = "",
            debug = false,
            autoStart = true,
            autoBalanceTeams = true,
            restorePlayerState = true,
            returnToLobbyAfterMatch = true,
            lobby = GameConfig.LobbyConfig(true, true, true),
        ),
        game = GameConfig.GameRulesConfig(2, 16, 3, 600, 5, false),
        health = GameConfig.HealthConfig(
            maximumPercent = 100,
            startingPercent = 100,
            regenerationEnabled = true,
            regenerationIntervalSeconds = 120,
            regenerationAmountPercent = 5,
        ),
        waterball = GameConfig.WaterBallConfig(
            startingAmount = 5,
            additionalPerMinute = 1,
            maximumHeld = 64,
            itemMaterial = "SNOWBALL",
            displayName = "Test Ball",
            lore = emptyList(),
            customModelData = 0,
            enchantedGlint = false,
        ),
        combat = GameConfig.CombatConfig(5, 15, 5, 3, friendlyFire = false),
        effects = GameConfig.EffectsConfig(true, true, true, true, 20, true),
        hud = GameConfig.HudConfig(true, 10, true, false),
        disconnect = GameConfig.DisconnectConfig(true, false, 30),
        statistics = GameConfig.StatisticsConfig(true, "SQLITE"),
        integrations = GameConfig.IntegrationsConfig(true),
        snowball = GameConfig.SnowballConfig(
            velocity = 3.0,
            damageMobs = false,
            mobDamage = 2.0,
            tracker = GameConfig.SnowballConfig.TrackerConfig(armorStandEnabled = false),
            knockback = GameConfig.SnowballConfig.KnockbackConfig(horizontal = 0.35, vertical = 0.15),
        ),
        feedback = GameConfig.FeedbackConfig(
            subtitleFadeInTicks = 2,
            subtitleStayTicks = 30,
            subtitleFadeOutTicks = 5,
            showAttackerFeedback = true,
            showVictimFeedback = true,
            showFriendlyFireFeedback = true,
            useHexGradients = true,
        ),
    )

    private val disabledRegenConfig = defaultConfig.copy(
        health = defaultConfig.health.copy(regenerationEnabled = false)
    )

    @Test
    fun `HP starts at configured starting percent`() {
        val svc = GameHealthService(defaultConfig)
        assertEquals(100, svc.startingHpForNewMatch())
    }

    @Test
    fun `5 damage reduces HP from 100 to 95`() {
        val svc = GameHealthService(defaultConfig)
        val newHp = svc.applyDamage(100, 5)
        assertEquals(95, newHp)
    }

    @Test
    fun `15 damage on 10 HP reduces to 0 (clamped, not negative)`() {
        val svc = GameHealthService(defaultConfig)
        val newHp = svc.applyDamage(10, 15)
        assertEquals(0, newHp)
    }

    @Test
    fun `HP never falls below zero`() {
        val svc = GameHealthService(defaultConfig)
        val newHp = svc.applyDamage(0, 50)
        assertEquals(0, newHp)
    }

    @Test
    fun `HP never exceeds maximum`() {
        val svc = GameHealthService(defaultConfig)
        val regenerated = svc.regenerate(98)
        // 98 + 5 = 103, capped at 100
        assertEquals(100, regenerated)
    }

    @Test
    fun `regeneration stops at the configured maximum`() {
        val svc = GameHealthService(defaultConfig)
        // Already at max — no further regeneration
        val result = svc.regenerate(100)
        assertEquals(100, result)
    }

    @Test
    fun `zero HP triggers elimination`() {
        val svc = GameHealthService(defaultConfig)
        assertTrue(svc.isEliminated(0))
    }

    @Test
    fun `positive HP is not eliminated`() {
        val svc = GameHealthService(defaultConfig)
        assertFalse(svc.isEliminated(1))
        assertFalse(svc.isEliminated(50))
        assertFalse(svc.isEliminated(100))
    }

    @Test
    fun `disabled regeneration produces no change`() {
        val svc = GameHealthService(disabledRegenConfig)
        val result = svc.regenerate(50)
        assertEquals(50, result)
    }

    @Test
    fun `applyDamage with negative input is clamped`() {
        val svc = GameHealthService(defaultConfig)
        val newHp = svc.applyDamage(50, -10)
        assertEquals(50, newHp)
    }
}
