package com.example.waterballonz.listeners

import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.arena.ArenaState
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot

/**
 * Listener responsible for the combat pipeline:
 *  - WaterBall throw: register projectile with match.
 *  - Projectile hit: route to CombatService.resolveProjectileHit.
 *  - Cancelled snowball damage: vanilla snowball damage never applies to
 *    participants; HP is mutated only by the CombatService.
 */
class CombatListener(private val registry: ServiceRegistry) : Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item
        if (item == null) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND && event.hand != EquipmentSlot.OFF_HAND) return
        if (!registry.combatService.waterBallItem.isWaterBall(item)) return

        val match = registry.matchManager.activeMatchOf(player) ?: run {
            event.isCancelled = true
            return
        }
        if (match.state != ArenaState.IN_GAME) {
            event.isCancelled = true
            return
        }
        // Allow the launch — Paper will spawn the snowball; we tag it in
        // ProjectileLaunchEvent below.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity as? Projectile ?: return
        if (projectile !is Snowball) return
        val shooter = projectile.shooter as? Player ?: return
        // Only track if the shooter is in an active match
        val match = registry.matchManager.activeMatchOf(shooter) ?: return
        if (!registry.combatService.waterBallItem.isWaterBall(shooter.inventory.itemInMainHand) &&
            !registry.combatService.waterBallItem.isWaterBall(shooter.inventory.itemInOffHand)) {
            // The shooter's hand doesn't hold a WaterBall — skip.
            return
        }
        registry.combatService.onWaterBallThrown(shooter, projectile)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity as? Projectile ?: return
        if (projectile !is Snowball) return

        val pdc = projectile.persistentDataContainer
        if (!pdc.has(registry.combatService.waterBallItem.markerKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
            return
        }

        val shooter = projectile.shooter as? Player
        if (shooter == null) {
            projectile.remove()
            return
        }
        val match = registry.matchManager.activeMatchOf(shooter)
        if (match == null || match.matchId != pdc.get(registry.combatService.waterBallItem.matchIdKey, org.bukkit.persistence.PersistentDataType.LONG) ?: -1L) {
            projectile.remove()
            return
        }

        val hitEntity = event.hitEntity
        registry.combatService.resolveProjectileHit(projectile, hitEntity, match)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDamageByProjectile(event: EntityDamageByEntityEvent) {
        val projectile = event.damager as? Projectile ?: return
        if (projectile !is Snowball) return
        // Damage applied via the projectile hit handler — bypass vanilla.
        if (event.entity is Player) {
            val session = registry.sessionService.sessionOf(event.entity.uniqueId)
            if (session != null) {
                event.isCancelled = true
            }
        }
    }
}
