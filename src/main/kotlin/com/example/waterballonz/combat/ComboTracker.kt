package com.example.waterballonz.combat

import java.util.UUID

/**
 * Pure-Kotlin record of one attacker's current combo streak.
 *
 * Used internally by [ComboTracker] — exposed here for test access only.
 */
data class ComboState(
    val consecutiveHits: Int,
    val lastHitAtMillis: Long,
)

/**
 * Tracks each attacker's combo streak and computes the damage for the next
 * successful enemy hit.
 *
 * Combo progression:
 *   hit 1 → 5 damage (configurable: `combat.base-damage-percent`)
 *   hit 2 → 10 damage (base + step)
 *   hit 3 → 15 damage (base + 2*step, capped at `combat.maximum-damage-percent`)
 *   hit 4+ → 15 damage (capped)
 *
 * The combo window is [comboWindowMillis] milliseconds. A hit *inside* the
 * window advances the combo; a hit *outside* the window resets to 1.
 *
 * Boundary rule: a hit arriving at exactly `lastHitAtMillis + comboWindowMillis`
 * is considered OUTSIDE the window — that is, the boundary belongs to the
 * reset edge. This matches the reference contract from the spec.
 *
 * This class is intentionally thread-safe so that async projectile-impact
 * handlers cannot race with the cleanup task.
 */
class ComboTracker(
    private val comboWindowMillis: Long,
) {
    private val states = java.util.concurrent.ConcurrentHashMap<UUID, ComboState>()

    init {
        require(comboWindowMillis > 0) {
            "Combo window must be greater than zero."
        }
    }

    /**
     * Record one successful enemy hit and return its damage percentage.
     *
     * `nowMillis` must be derived from a monotonic elapsed-time source
     * (`System.nanoTime() / 1_000_000` is the recommended source on Paper).
     *
     * Call this only after the target passes the game-state and team checks.
     */
    fun recordEnemyHit(
        attackerId: UUID,
        nowMillis: Long,
        baseDamage: Int = 5,
        step: Int = 5,
        cap: Int = 15,
    ): Int {
        val previous = states[attackerId]

        val nextCombo = if (
            previous == null ||
            nowMillis - previous.lastHitAtMillis > comboWindowMillis
        ) {
            1
        } else {
            previous.consecutiveHits + 1
        }

        states[attackerId] = ComboState(
            consecutiveHits = nextCombo,
            lastHitAtMillis = nowMillis,
        )

        val damage = baseDamage + (nextCombo - 1) * step
        return damage.coerceAtMost(cap)
    }

    fun reset(attackerId: UUID) {
        states.remove(attackerId)
    }

    fun resetAll() {
        states.clear()
    }

    fun getCombo(attackerId: UUID): Int =
        states[attackerId]?.consecutiveHits ?: 0

    fun lastHitAt(attackerId: UUID): Long? =
        states[attackerId]?.lastHitAtMillis

    fun cleanupDisconnectedPlayers(onlinePlayerIds: Set<UUID>) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in onlinePlayerIds) {
                iterator.remove()
            }
        }
    }
}
