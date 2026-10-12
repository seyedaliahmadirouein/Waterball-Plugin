package com.example.waterballonz.listeners

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.arena.ArenaState
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerVelocityEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent

/**
 * Listener responsible for:
 *  - Movement locking during countdown (move, teleport, sneak, velocity).
 *  - Boundary enforcement when configured.
 *  - Cancellation of vanilla damage on participants (HP is the source of
 *    truth; vanilla Minecraft damage must not kill a participant accidentally).
 *  - Food-level freeze while in a match.
 */
class ArenaListener(private val registry: ServiceRegistry) : Listener {

    private val plugin: com.example.waterballonz.WaterBallonZPlugin get() = registry.plugin

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val session = registry.sessionService.sessionOf(player.uniqueId) ?: return
        if (!session.movementLocked) {
            // Even when not locked, enforce boundary (if configured)
            val match = registry.matchManager.matchOf(session.arenaId) ?: return
            val boundary = match.definition.boundary ?: return
            if (match.state == ArenaState.IN_GAME) {
                val to = event.to ?: return
                if (!boundary.contains(to.world.name, to.x, to.y, to.z)) {
                    event.isCancelled = true
                }
            }
            return
        }
        // Movement is locked — allow head rotation only, cancel position changes.
        val from = event.from
        val to = event.to ?: return
        val movedBlocks = from.blockX != to.blockX || from.blockY != to.blockY || from.blockZ != to.blockZ
        if (movedBlocks) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val session = registry.sessionService.sessionOf(event.player.uniqueId) ?: return
        if (session.movementLocked) {
            // Allow plugin-owned teleports but block others.
            if (event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN &&
                event.cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onSneak(event: PlayerToggleSneakEvent) {
        val session = registry.sessionService.sessionOf(event.player.uniqueId) ?: return
        if (session.movementLocked) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onVelocity(event: PlayerVelocityEvent) {
        val session = registry.sessionService.sessionOf(event.player.uniqueId) ?: return
        if (session.movementLocked) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is org.bukkit.entity.Player) return
        val session = registry.sessionService.sessionOf(entity.uniqueId) ?: return
        // Block ALL vanilla damage to participants — HP is the source of truth.
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onFoodChange(event: FoodLevelChangeEvent) {
        val entity = event.entity as? org.bukkit.entity.Player ?: return
        val session = registry.sessionService.sessionOf(entity.uniqueId) ?: return
        // Keep food maxed during the match.
        entity.foodLevel = 20
        event.isCancelled = true
    }
}
