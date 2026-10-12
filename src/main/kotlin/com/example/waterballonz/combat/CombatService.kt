package com.example.waterballonz.combat

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.health.GameHealthService
import com.example.waterballonz.items.WaterBallItem
import com.example.waterballonz.match.Match
import com.example.waterballonz.match.MatchManager
import com.example.waterballonz.messages.MessageService
import com.example.waterballonz.persistence.StatisticsService
import com.example.waterballonz.player.PlayerSessionService
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Central combat authority.
 *
 * All combat entry points (projectile launch, projectile hit, melee damage)
 * MUST converge on this service. The state checks, team checks, combo
 * progression, HP mutation, effect playback, statistics recording, and
 * victory check all happen here so that the implementation is consistent.
 *
 * Anti-bug invariants enforced:
 *  - A projectile from an old match cannot damage a player in a new match.
 *  - A single projectile produces at most one damage resolution.
 *  - Friendly hits never advance the combo or apply damage.
 *  - Eliminated players cannot deal or receive damage.
 *  - Vanilla damage is bypassed entirely — HP changes only via this service.
 *  - HP never falls below 0 or exceeds the configured maximum.
 */
class CombatService(
    private val plugin: WaterBallonZPlugin,
    private val gameConfig: GameConfig,
    internal val comboTracker: ComboTracker,
    val healthService: GameHealthService,
    val waterBallItem: WaterBallItem,
    private val sessionService: PlayerSessionService,
    private val statisticsService: StatisticsService,
    private val messageService: MessageService,
) {
    /**
     * Determine whether the held item is an authentic WaterBall and the player
     * is in a state that allows throwing.
     */
    fun canThrowWaterBall(player: Player): Boolean {
        if (!sessionService.isAliveParticipant(player.uniqueId)) return false
        val match = plugin.registry.matchManager.activeMatchOf(player) ?: return false
        if (match.state != com.example.waterballonz.arena.ArenaState.IN_GAME) return false
        val main = player.inventory.itemInMainHand
        val off = player.inventory.itemInOffHand
        return waterBallItem.isWaterBall(main) || waterBallItem.isWaterBall(off)
    }

    /**
     * Handle a successful throw: consume ammo, spawn projectile marker, register
     * it with the MatchManager.
     */
    fun onWaterBallThrown(player: Player, projectile: Projectile) {
        if (!canThrowWaterBall(player)) {
            projectile.remove()
            return
        }
        val match = plugin.registry.matchManager.activeMatchOf(player) ?: run {
            projectile.remove()
            return
        }

        // Consume one ball from the hand that contains the WaterBall.
        val main = player.inventory.itemInMainHand
        val off = player.inventory.itemInOffHand
        when {
            waterBallItem.isWaterBall(main) -> {
                consumeOne(player, org.bukkit.inventory.EquipmentSlot.HAND)
            }
            waterBallItem.isWaterBall(off) -> {
                consumeOne(player, org.bukkit.inventory.EquipmentSlot.OFF_HAND)
            }
            else -> {
                projectile.remove()
                return
            }
        }

        // Tag projectile metadata via Bukkit's PersistentDataContainer on the entity.
        val pdc = projectile.persistentDataContainer
        pdc.set(waterBallItem.markerKey, PersistentDataType.BYTE, 1.toByte())
        pdc.set(waterBallItem.arenaBindingKey, PersistentDataType.STRING, match.arenaId())
        pdc.set(waterBallItem.matchIdKey, PersistentDataType.LONG, match.matchId)

        plugin.registry.matchManager.registerProjectile(projectile.uniqueId, player, match)

        // Decrement ammo counter and update HUD
        val session = sessionService.sessionOf(player.uniqueId) ?: return
        session.participant.ammo = (session.participant.ammo - 1).coerceAtLeast(0)
        session.participant.shotsFired++
        messageService.sendActionBar(player, "hud.actionbar.throw", "ammo" to session.participant.ammo)
    }

    private fun consumeOne(player: Player, slot: org.bukkit.inventory.EquipmentSlot) {
        val item: ItemStack? = when (slot) {
            org.bukkit.inventory.EquipmentSlot.HAND -> player.inventory.itemInMainHand
            org.bukkit.inventory.EquipmentSlot.OFF_HAND -> player.inventory.itemInOffHand
            else -> return
        }
        if (item == null || item.amount <= 0) return
        item.amount = item.amount - 1
        // Set back the modified stack so the inventory sees the change.
        when (slot) {
            org.bukkit.inventory.EquipmentSlot.HAND -> player.inventory.setItemInMainHand(if (item.amount == 0) null else item)
            org.bukkit.inventory.EquipmentSlot.OFF_HAND -> player.inventory.setItemInOffHand(if (item.amount == 0) null else item)
            else -> {}
        }
    }

    /**
     * The authoritative hit-resolution pipeline.
     *
     * Returns true if a valid damage resolution was applied; false otherwise
     * (projectile is invalid, target is teammate, target is dead, etc.).
     *
     * See the spec's "Projectile resolution pipeline" diagram — this method
     * implements it exactly.
     */
    fun resolveProjectileHit(
        projectile: Projectile,
        hitEntity: org.bukkit.entity.Entity?,
        match: Match,
    ): Boolean {
        // 1. Validate projectile marker
        val pdc = projectile.persistentDataContainer
        if (!pdc.has(waterBallItem.markerKey, PersistentDataType.BYTE)) {
            projectile.remove()
            return false
        }

        // 2. Resolve the MatchManager-side info (arena binding, shooter, etc.)
        val info = plugin.registry.matchManager.resolveProjectile(projectile.uniqueId, match)
        if (info == null || info.resolved || info.matchId != match.matchId) {
            projectile.remove()
            return false
        }

        // 3. Validate match state
        if (match.state != com.example.waterballonz.arena.ArenaState.IN_GAME) {
            projectile.remove()
            return false
        }

        // 4. Resolve shooter
        val shooter = Bukkit.getPlayer(info.shooterId)
        if (shooter == null || !shooter.isOnline) {
            projectile.remove()
            return false
        }

        val shooterSession = sessionService.sessionOf(shooter.uniqueId)
        if (shooterSession == null || !shooterSession.participant.alive) {
            projectile.remove()
            return false
        }

        // 5. Validate target is an eligible player
        if (hitEntity == null || hitEntity !is Player) {
            projectile.remove()
            return false
        }
        val target = hitEntity as Player
        val targetSession = sessionService.sessionOf(target.uniqueId)
        if (targetSession == null || !targetSession.participant.alive) {
            projectile.remove()
            return false
        }

        // 6. Check team membership
        if (shooterSession.participant.team == targetSession.participant.team && !gameConfig.combat.friendlyFire) {
            // Friendly hit — no damage, no combo advance, no stats.
            projectile.remove()
            return false
        }

        // 7. Calculate combo damage
        val nowMillis = System.currentTimeMillis()
        val damage = comboTracker.recordEnemyHit(
            attackerId = shooter.uniqueId,
            nowMillis = nowMillis,
            baseDamage = gameConfig.combat.baseDamagePercent,
            step = gameConfig.combat.damagePerComboStep,
            cap = gameConfig.combat.maximumDamagePercent,
        )

        // 8. Apply custom HP damage
        val newHp = healthService.applyDamage(targetSession.participant.hp, damage)
        targetSession.participant.hp = newHp
        targetSession.participant.damageDealt += 0 // not the shooter's
        shooterSession.participant.successfulHits++
        shooterSession.participant.comboHits = comboTracker.getCombo(shooter.uniqueId)
        shooterSession.participant.damageDealt += damage
        if (shooterSession.participant.comboHits > shooterSession.participant.highestCombo) {
            shooterSession.participant.highestCombo = shooterSession.participant.comboHits
        }

        // 9. Play effects
        playHitEffects(target.location, damage, shooter, target)

        // Update HUDs immediately so combat feedback feels responsive
        messageService.sendActionBar(target, "hud.actionbar.damaged",
            "hp" to newHp, "damage" to damage)
        messageService.sendActionBar(shooter, "hud.actionbar.hit",
            "combo" to shooterSession.participant.comboHits, "damage" to damage)

        // Record statistic atomically (no blocking I/O on main thread)
        if (gameConfig.statistics.enabled) {
            statisticsService.recordHitAsync(shooter.uniqueId, damage)
        }

        // 10. Check elimination / victory
        projectile.remove()
        if (newHp <= 0) {
            // Record elimination statistics (shooter scored an elimination)
            if (gameConfig.statistics.enabled) {
                statisticsService.recordEliminationAsync(shooter.uniqueId, target.uniqueId)
            }
            plugin.registry.matchManager.eliminate(target, match)
        }
        return true
    }

    private fun playHitEffects(location: Location, damage: Int, shooter: Player, target: Player) {
        if (gameConfig.effects.waterParticlesEnabled) {
            location.world?.spawnParticle(
                Particle.CLOUD,
                location.clone().add(0.0, 1.0, 0.0),
                gameConfig.effects.particleCount.coerceIn(1, 200),
                0.3, 0.5, 0.3,
                0.05,
            )
            location.world?.spawnParticle(
                Particle.FALLING_WATER,
                location.clone().add(0.0, 1.0, 0.0),
                gameConfig.effects.particleCount.coerceIn(1, 200),
                0.4, 0.6, 0.4,
                0.05,
            )
        }
        if (gameConfig.effects.splashSoundEnabled) {
            location.world?.playSound(location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0f, 1.2f)
        }
    }
}
