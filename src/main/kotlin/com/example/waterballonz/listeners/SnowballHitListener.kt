package com.example.waterballonz.listeners

import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.combat.service.CollisionResolver
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent

/**
 * Listener responsible for the snowball HIT phase.
 *
 * Per spec section 2 — "Fix snowball collision detection": use
 * `ProjectileHitEvent` (the canonical Bukkit/Paper event for "a projectile
 * just hit something") instead of relying solely on
 * `EntityDamageByEntityEvent`, which can be unreliable for snowballs.
 *
 * Per spec section 12 — "Better listeners": this listener does almost no
 * business logic. It just:
 *  1. Filters to snowball projectiles that have our PDC marker.
 *  2. Delegates the resolution to [CollisionResolver], which has the
 *     full hit pipeline (tracker lookup, team check, damage, knockback,
 *     feedback, elimination, cleanup).
 *
 * `ignoreCancelled = false` is intentional — we want to fire even if some
 * other plugin cancelled the event, because our `CollisionResolver` is
 * idempotent and our tracker needs to clean up.
 */
class SnowballHitListener(private val registry: ServiceRegistry) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity as? Projectile ?: return
        if (projectile !is Snowball) return

        // Quick PDC-marker check — fast-reject snowballs that aren't ours.
        val pdc = projectile.persistentDataContainer
        if (!pdc.has(
                registry.combatService.waterBallItem.markerKey,
                org.bukkit.persistence.PersistentDataType.BYTE
            )) return

        // Delegate to the collision resolver.
        val hitEntity = event.hitEntity
        val resolution = registry.collisionResolver.resolve(projectile, hitEntity)

        // Log debug info when enabled.
        if (registry.gameConfig.general.debug) {
            registry.plugin.logger.info("[Snowball] Hit: $resolution (entity=$hitEntity)")
        }
    }
}
