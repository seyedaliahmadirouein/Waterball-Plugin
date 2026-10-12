package com.example.waterballonz.player

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import org.bukkit.entity.Player

/**
 * Saves and restores everything the plugin modifies on a player.
 *
 * Snapshot is taken when the player joins an arena and restored when they
 * leave. [restore] is idempotent — restoring twice is a no-op.
 */
class PlayerSnapshotService(
    private val plugin: WaterBallonZPlugin,
    private val config: GameConfig,
) {
    private val snapshots: MutableMap<java.util.UUID, PlayerSnapshot> = mutableMapOf()
    private val restored: MutableSet<java.util.UUID> = mutableSetOf()

    fun capture(player: Player): PlayerSnapshot {
        val snap = PlayerSnapshot(
            player = player,
            inventoryContents = player.inventory.contents,
            armorContents = player.inventory.armorContents,
            offhandItem = player.inventory.itemInOffHand.clone(),
            gameMode = player.gameMode,
            originalLocation = player.location.clone(),
            health = player.health,
            maxHealth = player.maxHealth,
            food = player.foodLevel,
            saturation = player.saturation,
            exp = player.exp,
            level = player.level,
            effects = player.activePotionEffects.toList(),
            fireTicks = player.fireTicks,
            allowFlight = player.allowFlight,
            isFlying = player.isFlying,
            fallDistance = player.fallDistance,
            remainingAir = player.remainingAir,
        )
        snapshots[player.uniqueId] = snap
        restored.remove(player.uniqueId)
        return snap
    }

    fun restore(player: Player): Boolean {
        if (!config.general.restorePlayerState) return false
        val uid = player.uniqueId
        // Make sure we only restore once — even across multiple leave paths.
        if (restored.contains(uid)) return false
        val snap = snapshots[uid] ?: return false

        player.inventory.contents = snap.inventoryContents
        player.inventory.armorContents = snap.armorContents
        player.inventory.setItemInOffHand(snap.offhandItem)
        player.gameMode = snap.gameMode
        player.health = snap.health
        player.maxHealth = snap.maxHealth
        player.foodLevel = snap.food
        player.saturation = snap.saturation
        player.exp = snap.exp
        player.level = snap.level
        player.fireTicks = snap.fireTicks
        player.allowFlight = snap.allowFlight
        player.isFlying = snap.isFlying
        player.fallDistance = snap.fallDistance
        player.remainingAir = snap.remainingAir

        // Clear active effects, then re-apply the originals.
        for (effect in player.activePotionEffects.toList()) {
            player.removePotionEffect(effect.type)
        }
        for (effect in snap.effects) player.addPotionEffect(effect)

        restored.add(uid)
        return true
    }

    fun dropSnapshot(playerId: java.util.UUID) {
        snapshots.remove(playerId)
        restored.remove(playerId)
    }

    fun hasSnapshot(playerId: java.util.UUID): Boolean = snapshots.containsKey(playerId)

    fun snapshotOf(playerId: java.util.UUID): PlayerSnapshot? = snapshots[playerId]

    fun restoreOriginalLocation(player: Player): Boolean {
        val snap = snapshots[player.uniqueId] ?: return false
        return player.teleport(snap.originalLocation)
    }

    fun clearAll() {
        snapshots.clear()
        restored.clear()
    }
}
