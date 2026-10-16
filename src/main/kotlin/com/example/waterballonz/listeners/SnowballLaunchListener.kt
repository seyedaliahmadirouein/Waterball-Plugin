package com.example.waterballonz.listeners

import com.example.waterballonz.arena.ArenaState
import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.items.WaterBallItem
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Listener responsible for the snowball THROW phase.
 *
 * Per spec section 2 — "Fix snowball collision detection": the original
 * bug was that vanilla Bukkit threw the snowball and decremented the
 * player's stack BEFORE our `ProjectileLaunchEvent` handler ran, so the
 * handler couldn't find the WaterBall in the player's hand and either
 * removed the projectile (1-ball case) or double-consumed it (2+ balls).
 *
 * The fix: **cancel `PlayerInteractEvent` and spawn the snowball manually**
 * via `ProjectileSpawner`. This guarantees:
 *
 *  - Exactly one WaterBall is consumed per throw (no double-consumption).
 *  - The spawned snowball always has the PDC marker + thrower UUID/team.
 *  - The ProjectileTracker always has an entry for the projectile.
 *
 * Per spec section 12 — "Better listeners": this listener ONLY handles
 * the throw detection and delegates the actual spawning to
 * [com.example.waterballonz.combat.projectile.ProjectileSpawner].
 */
class SnowballLaunchListener(private val registry: ServiceRegistry) : Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        // Only handle right-clicks (the action that throws a snowball).
        if (event.action != Action.RIGHT_CLICK_AIR &&
            event.action != Action.RIGHT_CLICK_BLOCK) return

        // Only handle main-hand and off-hand interactions (not the hotbar).
        if (event.hand != EquipmentSlot.HAND && event.hand != EquipmentSlot.OFF_HAND) return

        val player = event.player
        val item = event.item ?: return
        val waterBallItem: WaterBallItem = registry.combatService.waterBallItem

        // Only intercept if the held item is a WaterBall — vanilla snowballs
        // and other items go through the normal Bukkit flow.
        if (!waterBallItem.isWaterBall(item)) return

        // Check that the player is in an active IN_GAME match. If they're
        // not, cancel the throw so they don't waste ammo outside a match.
        val session = registry.sessionService.sessionOf(player.uniqueId)
        if (session == null) {
            // Player isn't in a session — let vanilla handle (or not) the throw.
            // We don't cancel so vanilla throws a plain snowball.
            return
        }

        val match = registry.matchManager.matchOf(session.arenaId)
        if (match == null || match.state != ArenaState.IN_GAME) {
            // Player is in a session but match isn't in IN_GAME state
            // (e.g. COUNTDOWN or ENDING). Cancel the throw — no ammo
            // should be wasted during countdown.
            event.isCancelled = true
            registry.messageService.send(player, "error.throw_not_allowed")
            return
        }

        if (!session.participant.alive) {
            // Eliminated players cannot throw.
            event.isCancelled = true
            return
        }

        // === THE BUG FIX ===
        // Cancel the vanilla throw so we control the entire flow:
        //   1. Vanilla won't decrement the snowball stack.
        //   2. Vanilla won't spawn a snowball entity.
        //   3. We spawn the snowball ourselves via ProjectileSpawner.
        //   4. We decrement the snowball stack ourselves, exactly once.
        event.isCancelled = true

        // Delegate to the spawner — does the actual snowball spawn + PDC
        // tagging + tracker registration + ammo consumption.
        registry.projectileSpawner.spawnFor(player)
    }
}
