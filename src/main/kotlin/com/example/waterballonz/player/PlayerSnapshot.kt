package com.example.waterballonz.player

import com.example.waterballonz.team.Team
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

/**
 * Snapshot of a player's pre-game state. Used by [PlayerSnapshotService] to
 * restore everything WaterBallonZ modifies when a player leaves a match or
 * is reconnected after a server shutdown.
 */
data class PlayerSnapshot(
    val player: Player,
    val inventoryContents: Array<ItemStack?>,
    val armorContents: Array<ItemStack?>,
    val offhandItem: ItemStack?,
    val gameMode: GameMode,
    val originalLocation: Location,
    val health: Double,
    val maxHealth: Double,
    val food: Int,
    val saturation: Float,
    val exp: Float,
    val level: Int,
    val effects: List<PotionEffect>,
    val fireTicks: Int,
    val allowFlight: Boolean,
    val isFlying: Boolean,
    val fallDistance: Float,
    val remainingAir: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerSnapshot) return false
        return player.uniqueId == other.player.uniqueId
    }

    override fun hashCode(): Int = player.uniqueId.hashCode()
}
