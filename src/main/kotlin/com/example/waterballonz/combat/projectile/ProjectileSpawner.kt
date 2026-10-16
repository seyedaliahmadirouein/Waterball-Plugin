package com.example.waterballonz.combat.projectile

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.ArenaState
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.items.WaterBallItem
import com.example.waterballonz.match.Match
import com.example.waterballonz.match.MatchManager
import com.example.waterballonz.player.PlayerSessionService
import com.example.waterballonz.team.Team
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Spawns a WaterBall snowball projectile with full metadata and registers
 * it with the [ProjectileTracker].
 *
 * THIS CLASS IS THE FIX FOR THE SNOWBALL-HIT BUG.
 *
 * The old code let vanilla Bukkit throw the snowball and then tried to
 * post-tag it in `ProjectileLaunchEvent`. That approach failed because:
 *
 *  1. Vanilla decremented the snowball stack BEFORE `ProjectileLaunchEvent`
 *     fired, so the launch handler couldn't find the WaterBall in the
 *     player's hand anymore → entered the `else` branch → called
 *     `projectile.remove()` → the snowball never flew.
 *
 *  2. When the player had 2+ WaterBalls, vanilla decremented 1, then the
 *     handler decremented another → double consumption.
 *
 * The new approach: **take over the throw entirely**.
 *
 *  1. Cancel `PlayerInteractEvent` so vanilla never throws.
 *  2. Spawn the Snowball manually via `World.spawn(Location, Snowball::class.java)`
 *     at the player's eye location.
 *  3. Set the shooter and velocity from the player's look direction.
 *  4. Tag the snowball's PersistentDataContainer with:
 *       - marker byte (1)
 *       - thrower UUID (string)
 *       - thrower team (string: "red" / "blue")
 *       - match ID (long)
 *  5. Register with [ProjectileTracker] (centralized, plugin-wide).
 *  6. Optionally spawn an [ArmorStandFollower] if enabled in config.
 *  7. Consume exactly ONE WaterBall from the player's hand.
 *
 * Result: the snowball always flies, always has correct metadata, never
 * double-consumes, and the tracker is always populated.
 */
class ProjectileSpawner(
    private val plugin: WaterBallonZPlugin,
    private val gameConfig: GameConfig,
    private val waterBallItem: WaterBallItem,
    private val tracker: ProjectileTracker,
    private val armorStandFollower: ArmorStandFollower,
    private val sessionService: PlayerSessionService,
    private val matchManager: MatchManager,
) {
    /**
     * Spawn a WaterBall projectile for [player].
     *
     * Preconditions:
     *  - Player must be a live participant in an active IN_GAME match.
     *  - Player must be holding a WaterBall in either hand.
     *
     * @return the spawned Snowball, or null if the spawn was rejected
     *   (player not in match, wrong state, no WaterBall in hand, etc.).
     */
    fun spawnFor(player: Player): Snowball? {
        // 1. Validate the shooter
        val session = sessionService.sessionOf(player.uniqueId)
            ?: return null
        if (!session.participant.alive) return null

        val match = matchManager.matchOf(session.arenaId)
            ?: return null
        if (match.state != ArenaState.IN_GAME) return null

        // 2. Determine which hand holds the WaterBall and consume exactly one.
        val slot = findWaterBallHand(player)
            ?: return null
        consumeOne(player, slot)

        // 3. Spawn the snowball manually (we cancelled vanilla's throw in the listener).
        val eyeLoc = player.eyeLocation
        val snowball = spawnSnowball(eyeLoc, player) ?: return null

        // 4. Tag the snowball's PDC. This is the authoritative marker that
        //    identifies it as a WaterBall — never the display name or material.
        tagProjectile(snowball, player, session.participant.team, match)

        // 5. Register with the central tracker.
        tracker.register(
            snowball = snowball,
            throwerId = player.uniqueId,
            throwerName = player.name,
            throwerTeam = session.participant.team,
            arenaId = match.arenaId(),
            matchId = match.matchId,
            armorStandId = maybeSpawnFollower(snowball),
        )

        // 6. Update ammo counter and play throw sound.
        session.participant.ammo = (session.participant.ammo - 1).coerceAtLeast(0)
        session.participant.shotsFired++

        if (gameConfig.effects.splashSoundEnabled) {
            player.playSound(eyeLoc, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1.2f)
        }

        plugin.logger.fine("[Snowball] Launched: thrower=${player.name} team=${session.participant.team} " +
            "projectile=${snowball.uniqueId} match=${match.matchId}")

        return snowball
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private fun findWaterBallHand(player: Player): EquipmentSlot? {
        val main = player.inventory.itemInMainHand
        val off = player.inventory.itemInOffHand
        return when {
            waterBallItem.isWaterBall(main) && main.amount > 0 -> EquipmentSlot.HAND
            waterBallItem.isWaterBall(off) && off.amount > 0 -> EquipmentSlot.OFF_HAND
            else -> null
        }
    }

    private fun consumeOne(player: Player, slot: EquipmentSlot) {
        val stack: ItemStack? = when (slot) {
            EquipmentSlot.HAND -> player.inventory.itemInMainHand
            EquipmentSlot.OFF_HAND -> player.inventory.itemInOffHand
            else -> return
        }
        if (stack == null || stack.amount <= 0) return
        val newAmount = stack.amount - 1
        if (newAmount == 0) {
            // Clear the slot — leaving an empty AIR ItemStack in main hand
            // confuses some Minecraft clients.
            when (slot) {
                EquipmentSlot.HAND -> player.inventory.setItemInMainHand(null)
                EquipmentSlot.OFF_HAND -> player.inventory.setItemInOffHand(null)
                else -> {}
            }
        } else {
            stack.amount = newAmount
            when (slot) {
                EquipmentSlot.HAND -> player.inventory.setItemInMainHand(stack)
                EquipmentSlot.OFF_HAND -> player.inventory.setItemInOffHand(stack)
                else -> {}
            }
        }
        // Force the player's client to see the inventory update immediately.
        player.updateInventory()
    }

    private fun spawnSnowball(eyeLoc: Location, player: Player): Snowball? {
        val world = eyeLoc.world ?: return null
        // Spawn slightly in front of the player's eyes so the snowball doesn't
        // collide with the thrower's own hitbox.
        val spawnLoc = eyeLoc.add(eyeLoc.direction.clone().multiply(0.5))
        val snowball = world.spawn(spawnLoc, Snowball::class.java) { entity ->
            entity.setShooter(player)
            // Approximate vanilla snowball velocity (3.0 blocks/sec) — players
            // expect the snowball to behave like a vanilla throw.
            val vel = eyeLoc.direction.clone().multiply(3.0)
            entity.velocity = vel
        }
        return snowball
    }

    private fun tagProjectile(
        snowball: Snowball,
        player: Player,
        team: Team,
        match: Match,
    ) {
        val pdc = snowball.persistentDataContainer
        pdc.set(waterBallItem.markerKey, PersistentDataType.BYTE, 1.toByte())
        pdc.set(waterBallItem.arenaBindingKey, PersistentDataType.STRING, match.arenaId())
        pdc.set(waterBallItem.matchIdKey, PersistentDataType.LONG, match.matchId)
        // Extra metadata for the new tracker — kept in sync with the central
        // ProjectileTracker's ProjectileData so we have a redundant on-entity
        // source of truth that survives even if the in-memory tracker entry
        // gets reaped.
        pdc.set(THROWER_UUID_KEY, PersistentDataType.STRING, player.uniqueId.toString())
        pdc.set(THROWER_TEAM_KEY, PersistentDataType.STRING, team.id)
    }

    /**
     * Spawn an ArmorStand follower if enabled in config, otherwise return null.
     */
    private fun maybeSpawnFollower(snowball: Snowball): UUID? {
        if (!gameConfig.snowball.tracker.armorStandEnabled) return null
        val stand = armorStandFollower.spawn(snowball.location, snowball.uniqueId) ?: return null
        return stand.uniqueId
    }

    val throwerUuidKey: NamespacedKey get() = THROWER_UUID_KEY
    val throwerTeamKey: NamespacedKey get() = THROWER_TEAM_KEY

    private val THROWER_UUID_KEY = NamespacedKey(plugin, "snowball_thrower_uuid")
    private val THROWER_TEAM_KEY = NamespacedKey(plugin, "snowball_thrower_team")
}
