package com.example.waterballonz.listeners

import com.example.waterballonz.bootstrap.ServiceRegistry
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ProjectileHitEvent

/**
 * Listener responsible for CLEANUP of WaterBall projectiles and their
 * ArmorStand followers.
 *
 * Per spec section 14 — "Better cleanup": every projectile must eventually
 * be cleaned up. Cleanup must be idempotent.
 *
 * This listener handles:
 *
 *  - `EntityDamageByEntityEvent` from a snowball → **cancel vanilla damage**.
 *    Per spec section 9: "Normal Minecraft mechanics do not accidentally
 *    override the custom behavior." Vanilla snowball damage is normally 0
 *    on players, but if a plugin or config enables it, we cancel it here.
 *
 *  - `EntityRemoveEvent` (1.17+) → when a snowball entity is removed by
 *    Bukkit for any reason (e.g. expired, flew into unloaded chunk,
 *    unloaded with the world), we unregister its tracker entry.
 *
 *  - `EntityDeathEvent` (rare for projectiles, but possible if a plugin
 *    kills it) → same cleanup.
 *
 * Per spec section 12 — "Better listeners": this listener only does
 * cleanup / cancellation — no combat logic.
 */
class SnowballCleanupListener(private val registry: ServiceRegistry) : Listener {

    /**
     * Cancel vanilla snowball damage to participants. We apply our own custom
     * damage via [com.example.waterballonz.combat.service.CollisionResolver].
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDamageByProjectile(event: EntityDamageByEntityEvent) {
        val projectile = event.damager as? Projectile ?: return
        if (projectile !is Snowball) return

        val pdc = projectile.persistentDataContainer
        if (!pdc.has(
                registry.combatService.waterBallItem.markerKey,
                org.bukkit.persistence.PersistentDataType.BYTE
            )) return

        // It's a WaterBall — cancel vanilla damage. Custom damage is applied
        // via CollisionResolver (called from SnowballHitListener).
        event.isCancelled = true
    }

    /**
     * Modern 1.17+ event for entity removal. Fire for every entity, but we
     * only act on WaterBalls. This catches snowballs that:
     *  - Expired (lifetime exceeded).
     *  - Flew into an unloaded chunk.
     *  - Were removed by another plugin.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemove(event: EntityRemoveEvent) {
        val entity = event.entity
        if (entity !is Snowball) return
        val pdc = entity.persistentDataContainer
        if (!pdc.has(
                registry.combatService.waterBallItem.markerKey,
                org.bukkit.persistence.PersistentDataType.BYTE
            )) return
        // Idempotent — safe to call multiple times.
        registry.projectileTracker.unregister(entity.uniqueId)
    }

    /**
     * Defensive: if the snowball is killed (rare, but some plugins do this),
     * clean up the tracker entry.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity !is Snowball) return
        val pdc = entity.persistentDataContainer
        if (!pdc.has(
                registry.combatService.waterBallItem.markerKey,
                org.bukkit.persistence.PersistentDataType.BYTE
            )) return
        registry.projectileTracker.unregister(entity.uniqueId)
    }
}
