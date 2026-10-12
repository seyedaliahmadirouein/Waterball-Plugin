package com.example.waterballonz.health

import com.example.waterballonz.configuration.GameConfig

/**
 * Pure-Kotlin custom HP service.
 *
 * HP is an integer percentage in `[0, 100]` (or whatever `health.maximum-percent`
 * is configured to). Vanilla Minecraft health is NOT used by this service.
 *
 * All mutations return the resulting HP value so callers can react to HP
 * thresholds (e.g. elimination at 0) without a second lookup.
 */
class GameHealthService(private val config: GameConfig) {

    val maxHp: Int get() = config.health.maximumPercent
    val startingHp: Int get() = config.health.startingPercent
    val regenIntervalSeconds: Int get() = config.health.regenerationIntervalSeconds
    val regenAmount: Int get() = config.health.regenerationAmountPercent
    val regenEnabled: Boolean get() = config.health.regenerationEnabled

    /**
     * Apply damage and return the resulting HP. Negative inputs are clamped
     * to zero. The damage parameter is a raw percentage (already computed by
     * the combo service).
     */
    fun applyDamage(currentHp: Int, damage: Int): Int =
        (currentHp - damage.coerceAtLeast(0)).coerceIn(0, maxHp)

    /**
     * Apply one regeneration tick. Returns the new HP value.
     */
    fun regenerate(currentHp: Int): Int =
        if (regenEnabled) (currentHp + regenAmount).coerceAtMost(maxHp)
        else currentHp

    /**
     * Compute whether [currentHp] represents an eliminated player.
     */
    fun isEliminated(currentHp: Int): Boolean = currentHp <= 0

    /**
     * Compute the starting HP for a new match.
     */
    fun startingHpForNewMatch(): Int = startingHp.coerceIn(0, maxHp)
}
