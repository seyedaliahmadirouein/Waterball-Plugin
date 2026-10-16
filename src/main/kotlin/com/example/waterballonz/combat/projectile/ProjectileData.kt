package com.example.waterballonz.combat.projectile

import com.example.waterballonz.team.Team
import java.util.UUID

/**
 * Immutable snapshot of everything we need to know about a tracked WaterBall
 * projectile, independent of whether the snowball entity itself is still alive.
 *
 * Stored in [ProjectileTracker] keyed by the projectile's [java.util.UUID].
 * Survives the snowball entity being garbage-collected or unloaded — we use
 * this for hit attribution, friendly-fire checks, and cleanup tracking.
 *
 * @property projectileId     UUID of the org.bukkit.entity.Snowball entity.
 * @property throwerId        UUID of the player who threw the snowball.
 * @property throwerName      Cached player name at throw-time — only used for
 *                            debug logging, NEVER for identity checks.
 * @property throwerTeam      Team the thrower was on at throw-time.
 * @property arenaId          Arena in which the snowball was thrown.
 * @property matchId          Match ID at throw-time. Hits are rejected if the
 *                            projectile's matchId differs from the active
 *                            match's matchId (prevents stale-projectile leaks).
 * @property launchTimeMillis System.currentTimeMillis() at launch.
 * @property armorStandId     UUID of the optional invisible ArmorStand that
 *                            follows this snowball, or null when the
 *                            ArmorStand follower is disabled in config.
 */
data class ProjectileData(
    val projectileId: UUID,
    val throwerId: UUID,
    val throwerName: String,
    val throwerTeam: Team,
    val arenaId: String,
    val matchId: Long,
    val launchTimeMillis: Long,
    val armorStandId: UUID?,
) {
    /**
     * Age of the projectile in milliseconds. Used by [ProjectileTracker]'s
     * reaper task to garbage-collect projectiles that never hit anything
     * (e.g. flew into an unloaded chunk or were removed externally).
     */
    fun ageMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis - launchTimeMillis
}
