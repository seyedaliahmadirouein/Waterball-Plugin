package com.example.waterballonz.combat.projectile

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Snowball
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Optional invisible ArmorStand follower.
 *
 * When `snowball.tracker.armor-stand-enabled: true` in config.yml, a tiny
 * invisible ArmorStand is spawned next to each thrown snowball. Its purpose:
 *
 *  - Provide a **fallback ownership path**. If the snowball's `shooter`
 *    field is null by the time `ProjectileHitEvent` fires (very rare, but
 *    possible across chunk boundaries), we can still look up the thrower
 *    by querying the ArmorStand's PersistentDataContainer.
 *
 *  - The ArmorStand is **never** treated as a hit target — see
 *    [ProjectileTracker.isTrackingArmorStand] which the collision resolver
 *    consults to skip our own followers.
 *
 * The ArmorStand is:
 *  - invisible (no base plate, no name, no equipment)
 *  - no gravity (it's teleported each tick by the tracker, not moved by physics)
 *  - non-targetable (marker = true where supported)
 *  - invulnerable
 *  - tagged with the snowball's UUID via PersistentDataContainer
 *
 * IMPORTANT: this is a fallback/secondary tracking mechanism. If the
 * plugin's main [ProjectileTracker] (which uses the snowball's own PDC +
 * UUID-keyed map) is sufficient on its own — and it usually is — the
 * ArmorStand follower can be safely disabled in config.
 */
class ArmorStandFollower(
    private val plugin: WaterBallonZPlugin,
    private val gameConfig: GameConfig,
) {
    val linkedProjectileKey: NamespacedKey = NamespacedKey(plugin, "snowball_follower_of")

    /**
     * Spawn an invisible ArmorStand at [location] and tag it with the
     * [snowballId] it is following.
     *
     * @return the spawned ArmorStand, or null if spawning failed.
     */
    fun spawn(location: Location, snowballId: java.util.UUID): ArmorStand? {
        val world = location.world ?: return null
        return try {
            val stand = world.spawn(location, ArmorStand::class.java) { entity ->
                entity.isVisible = false
                entity.setGravity(false)
                entity.isInvulnerable = true
                entity.isSmall = true
                entity.isMarker = true   // 1.17+: marker stands are non-targetable
                entity.setAI(false)
                entity.isCustomNameVisible = false
                entity.setCanTick(false)
                // Tag with the snowball UUID we're following.
                entity.persistentDataContainer.set(
                    linkedProjectileKey,
                    PersistentDataType.STRING,
                    snowballId.toString(),
                )
            }
            stand
        } catch (e: Exception) {
            plugin.logger.warning("[Snowball] Failed to spawn ArmorStand follower: ${e.message}")
            null
        }
    }

    /**
     * Teleport the ArmorStand to follow its snowball. Called by the
     * centralized tracker task — there is exactly ONE such task for the
     * whole plugin, NOT one per projectile.
     */
    fun follow(stand: ArmorStand, snowball: Snowball) {
        if (!stand.isValid || stand.isDead) return
        if (!snowball.isValid || snowball.isDead) return
        stand.teleport(snowball.location)
    }

    /**
     * Resolve the snowball UUID that this ArmorStand is following.
     * Returns null if the ArmorStand isn't one of our followers (e.g. a
     * vanilla ArmorStand that some other plugin spawned).
     */
    fun resolveLinkedSnowballId(stand: ArmorStand): java.util.UUID? {
        val raw = stand.persistentDataContainer.get(
            linkedProjectileKey,
            PersistentDataType.STRING,
        ) ?: return null
        return try {
            java.util.UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
