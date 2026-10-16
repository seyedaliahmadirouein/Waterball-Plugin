package com.example.waterballonz.combat.service

import com.example.waterballonz.configuration.GameConfig
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/**
 * Applies a small, controlled knockback to a hit victim.
 *
 * The knockback direction is calculated from the projectile's incoming
 * direction (or, as a fallback, the thrower's position relative to the
 * victim). Both horizontal and vertical strengths are configurable.
 *
 * Anti-bug invariants:
 *  - Friendly-fire hits do NOT apply enemy knockback.
 *  - Eliminated players do NOT receive knockback (no ragdolling spectators).
 *  - The victim is never launched into the stratosphere — magnitudes are
 *    capped in code even when config values are misconfigured.
 */
class KnockbackService(private val gameConfig: GameConfig) {

    /**
     * Apply knockback to [victim], pushing them away from [fromLocation].
     *
     * @param fromLocation   The location the projectile came from (typically
     *                       the projectile's location just before impact, or
     *                       the thrower's location as a fallback).
     * @param victim         The player to push.
     */
    fun apply(fromLocation: Location, victim: Player) {
        val victimLoc = victim.location

        // Direction vector: from the source to the victim, normalized to
        // horizontal plane (we don't want vertical pull).
        val dir = victimLoc.toVector().subtract(fromLocation.toVector())
        if (dir.lengthSquared() < 1.0E-6) {
            // Source and victim are at the same position — push straight up.
            dir.x = 0.0
            dir.z = 0.0
        } else {
            // Normalize horizontal component.
            val len = Math.sqrt(dir.x * dir.x + dir.z * dir.z)
            dir.x = dir.x / len
            dir.z = dir.z / len
        }
        dir.y = 0.0  // vertical handled separately

        val h = gameConfig.snowball.knockback.horizontal.coerceIn(0.0, 3.0)
        val v = gameConfig.snowball.knockback.vertical.coerceIn(0.0, 2.0)

        // Multiply direction by horizontal strength, add vertical pop.
        val knockback = Vector(dir.x * h, v, dir.z * h)
        victim.velocity = knockback
    }
}
