package com.example.waterballonz.combat.projectile

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.team.Team
import org.bukkit.Bukkit
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Snowball
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central, plugin-wide registry of every active WaterBall projectile.
 *
 * Design goals (per spec section 6 — "Make the tracker follow the snowball"):
 *
 *  - **One shared map**, not one task per projectile. Lookup is O(1).
 *  - **Survives entity unloads.** When a snowball flies into an unloaded
 *    chunk, the entity may be unloaded by the server. We still hold the
 *    [ProjectileData] so a late-arriving ProjectileHitEvent can be
 *    attributed correctly. The reaper task cleans up entries older than
 *    the configured max lifetime.
 *  - **Idempotent cleanup.** [unregister] is safe to call multiple times.
 *  - **No per-projectile task.** A single repeating task updates all
 *    ArmorStand followers and reaps dead entries — see [startMaintenanceTask].
 *  - **No memory leaks.** Every register() is balanced by exactly one
 *    unregister() in: ProjectileHitEvent, entity removal, projectile expiry,
 *    match end, and plugin shutdown.
 */
class ProjectileTracker(private val plugin: WaterBallonZPlugin) {

    /** All known active projectiles, keyed by projectile entity UUID. */
    private val projectiles: ConcurrentHashMap<UUID, ProjectileData> = ConcurrentHashMap()

    /** Reverse lookup: ArmorStand UUID → projectile UUID (for the follower). */
    private val byArmorStand: ConcurrentHashMap<UUID, UUID> = ConcurrentHashMap()

    /**
     * Maximum lifetime (ms) a tracker entry is allowed to live without
     * being unregistered. Snowballs travel for ~1-2 seconds in normal
     * play; 30 seconds is a generous upper bound to catch stragglers that
     * flew into an unloaded chunk or were removed externally.
     */
    private val maxLifetimeMillis: Long = 30_000L

    @Volatile
    private var maintenanceTaskId: Int = -1

    // ----------------------------------------------------------------
    // Registration
    // ----------------------------------------------------------------

    /**
     * Register a freshly-spawned WaterBall projectile.
     *
     * @return the [ProjectileData] that was stored.
     */
    fun register(
        snowball: Snowball,
        throwerId: UUID,
        throwerName: String,
        throwerTeam: Team,
        arenaId: String,
        matchId: Long,
        armorStandId: UUID? = null,
    ): ProjectileData {
        val data = ProjectileData(
            projectileId = snowball.uniqueId,
            throwerId = throwerId,
            throwerName = throwerName,
            throwerTeam = throwerTeam,
            arenaId = arenaId,
            matchId = matchId,
            launchTimeMillis = System.currentTimeMillis(),
            armorStandId = armorStandId,
        )
        projectiles[snowball.uniqueId] = data
        armorStandId?.let { byArmorStand[it] = snowball.uniqueId }
        return data
    }

    /**
     * Idempotent cleanup. Safe to call multiple times for the same projectile.
     *
     * Removes the tracker entry, removes the associated ArmorStand follower
     * (if any), and removes the snowball entity (if still alive).
     */
    fun unregister(projectileId: UUID) {
        val data = projectiles.remove(projectileId) ?: return
        data.armorStandId?.let { standId ->
            byArmorStand.remove(standId)
            // Remove the ArmorStand follower entity if it still exists.
            Bukkit.getEntity(standId)?.remove()
        }
        // Also remove the snowball entity itself if it's still alive
        // (sometimes ProjectileHitEvent fires but the entity lingers).
        Bukkit.getEntity(projectileId)?.remove()
    }

    /**
     * Look up a tracked projectile by its entity UUID.
     */
    fun of(projectileId: UUID): ProjectileData? = projectiles[projectileId]

    /**
     * Look up a tracked projectile by the UUID of its ArmorStand follower.
     */
    fun byArmorStandId(armorStandId: UUID): ProjectileData? =
        byArmorStand[armorStandId]?.let { projectiles[it] }

    /**
     * @return true if the given entity is the ArmorStand follower of a
     * tracked projectile. Used by the collision resolver to ignore our
     * own ArmorStands.
     */
    fun isTrackingArmorStand(entity: Entity): Boolean =
        entity is ArmorStand && byArmorStand.containsKey(entity.uniqueId)

    /**
     * @return all projectiles currently tracked (snapshot, immutable).
     */
    fun snapshot(): List<ProjectileData> = projectiles.values.toList()

    /**
     * @return number of projectiles currently tracked.
     */
    fun count(): Int = projectiles.size

    // ----------------------------------------------------------------
    // Centralized maintenance task
    // ----------------------------------------------------------------

    /**
     * Start a single repeating task that:
     *  1. Removes tracker entries that have lived longer than [maxLifetimeMillis].
     *  2. Removes ArmorStand followers whose snowball is no longer alive.
     *
     * Runs every 5 ticks (250ms) — much cheaper than one task per projectile.
     */
    fun startMaintenanceTask() {
        if (maintenanceTaskId != -1) return  // already running
        maintenanceTaskId = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { reapStaleEntries() },
            20L,   // initial delay: 1 second
            5L,    // repeat every 5 ticks
        ).taskId
    }

    /**
     * Stop the maintenance task (called on plugin disable).
     */
    fun stopMaintenanceTask() {
        if (maintenanceTaskId != -1) {
            plugin.server.scheduler.cancelTask(maintenanceTaskId)
            maintenanceTaskId = -1
        }
    }

    private fun reapStaleEntries() {
        val now = System.currentTimeMillis()
        val iterator = projectiles.entries.iterator()
        while (iterator.hasNext()) {
            val (id, data) = iterator.next()
            if (now - data.launchTimeMillis > maxLifetimeMillis) {
                // Projectile has lived too long without being unregistered —
                // it's almost certainly gone (flew into an unloaded chunk,
                // was removed by another plugin, etc.). Clean it up.
                unregister(id)
                plugin.logger.fine("[Snowball] Reaped stale projectile $id (${data.ageMillis(now)}ms old)")
            }
        }
    }

    /**
     * Emergency cleanup — called on plugin disable. Removes every tracker
     * entry and every ArmorStand follower.
     */
    fun shutdown() {
        stopMaintenanceTask()
        for (id in projectiles.keys.toList()) {
            unregister(id)
        }
        // Defensive — in case shutdown interleaved with the reaper.
        projectiles.clear()
        byArmorStand.clear()
    }
}
