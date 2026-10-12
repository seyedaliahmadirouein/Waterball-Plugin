package com.example.waterballonz.listeners

import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.arena.ArenaState
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * Player lifecycle events:
 *  - Server join: send a "back to lobby" message if their snapshot exists.
 *  - Server quit: route the player out of any active match safely.
 *  - Respawn: ensure eliminated players are routed correctly.
 */
class PlayerLifecycleListener(private val registry: ServiceRegistry) : Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    fun onJoin(event: PlayerJoinEvent) {
        // Cleanup: if the player has a stale snapshot from a previous match,
        // the next /wbz leave will handle restoration. Nothing else to do.
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val session = registry.sessionService.sessionOf(player.uniqueId) ?: return
        val match = registry.matchManager.matchOf(session.arenaId)
        // Treat as a leave — apply eliminate-on-disconnect policy if needed.
        registry.matchManager.handlePlayerLeave(player, match, session, eliminateIfActive = true)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val session = registry.sessionService.sessionOf(player.uniqueId) ?: return
        val match = registry.matchManager.matchOf(session.arenaId)
        if (match == null) {
            // No match — restore snapshot & return to lobby
            registry.snapshotService.restore(player)
            registry.lobbyService.sendToLobby(player, session.preGameReturnLocation)
            return
        }
        // If participant is eliminated, send to spectator/lobby
        if (session.participant.eliminated) {
            val spec = match.definition.spectator?.resolve()
            if (spec != null) {
                event.respawnLocation = spec
            } else {
                val lobby = registry.lobbyService.resolve()
                if (lobby != null) event.respawnLocation = lobby
            }
        }
    }
}
