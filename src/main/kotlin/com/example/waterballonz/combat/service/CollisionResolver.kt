package com.example.waterballonz.combat.service

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.ArenaState
import com.example.waterballonz.combat.projectile.ProjectileData
import com.example.waterballonz.combat.projectile.ProjectileTracker
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.feedback.CombatFeedbackService
import com.example.waterballonz.match.Match
import com.example.waterballonz.match.MatchManager
import com.example.waterballonz.persistence.StatisticsService
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile

/**
 * Authoritative collision-resolution pipeline for WaterBall projectiles.
 *
 * Implements the spec's "Projectile resolution pipeline" diagram:
 *
 *   Projectile Impact
 *          |
 *          v
 *   Validate Projectile Marker (PDC)
 *          |
 *          v
 *   Resolve Projectile Tracker Entry
 *          |
 *          v
 *   Validate Match State (must be IN_GAME)
 *          |
 *          v
 *   Determine Collision Type (player / mob / armorstand / block / nothing)
 *          |
 *          +-------> Tracking ArmorStand → ignore (don't damage our own follower)
 *          |
 *          +-------> Block / nothing     → just cleanup, no combat
 *          |
 *          +-------> Mob                 → configurable damage (default: no)
 *          |
 *          +-------> Player              → team check → damage or friendly-fire feedback
 *          |
 *          v
 *   Apply Damage + Knockback + Effects + Feedback
 *          |
 *          v
 *   Check Elimination / Victory
 *          |
 *          v
 *   Cleanup (idempotent)
 *
 * Anti-bug invariants enforced:
 *  - A projectile from an old match cannot damage a player in a new match
 *    (the matchId stored in PDC + tracker is checked against the live match).
 *  - A single projectile produces at most one damage resolution (tracker
 *    entry is removed on the first resolution).
 *  - Friendly hits never apply damage or knockback or combo advancement.
 *  - Eliminated players cannot deal or receive damage.
 *  - Our own ArmorStand followers are never treated as combat victims.
 */
class CollisionResolver(
    private val plugin: WaterBallonZPlugin,
    private val gameConfig: GameConfig,
    private val tracker: ProjectileTracker,
    private val damageService: DamageService,
    private val knockbackService: KnockbackService,
    private val feedbackService: CombatFeedbackService,
    private val matchManager: MatchManager,
    private val statisticsService: StatisticsService,
) {

    /**
     * Result of resolving a collision. Returned so the listener can decide
     * whether to log debug info or take additional action.
     */
    sealed class Resolution {
        object NotWaterBall : Resolution()
        object NotTracked : Resolution()
        object StaleMatch : Resolution()
        object MatchNotInGame : Resolution()
        object HitTrackingArmorStand : Resolution()
        object HitBlock : Resolution()
        object HitNonPlayerEntity : Resolution()
        data class HitPlayer(val result: DamageService.DamageResult) : Resolution()
        object HitShooterSelf : Resolution()
    }

    /**
     * Resolve a projectile's impact.
     *
     * @param projectile The Bukkit projectile entity.
     * @param hitEntity The entity the projectile hit, or null if it hit a
     *                  block / air.
     * @return the [Resolution] outcome.
     */
    fun resolve(projectile: Projectile, hitEntity: Entity?): Resolution {
        // 1. Validate the projectile is one of ours (PDC marker check).
        val pdc = projectile.persistentDataContainer
        if (!pdc.has(plugin.registry.waterBallItem.markerKey,
                org.bukkit.persistence.PersistentDataType.BYTE)) {
            return Resolution.NotWaterBall
        }

        // 2. Look up tracker entry. If the tracker doesn't know about this
        //    projectile, it's either a stale snowball from a previous match
        //    or one we never registered — either way, just clean it up.
        val projectileId = projectile.uniqueId
        val data = tracker.of(projectileId) ?: run {
            tracker.unregister(projectileId)
            return Resolution.NotTracked
        }

        // 3. Resolve the match — must be IN_GAME for combat to be active.
        val match = matchManager.matchOf(data.arenaId)
        if (match == null || match.matchId != data.matchId) {
            tracker.unregister(projectileId)
            return Resolution.StaleMatch
        }
        if (match.state != ArenaState.IN_GAME) {
            tracker.unregister(projectileId)
            return Resolution.MatchNotInGame
        }

        // 4. Determine collision type.
        try {
            val resolution = when {
                hitEntity == null -> {
                    // Hit a block or air — just cleanup.
                    Resolution.HitBlock
                }
                tracker.isTrackingArmorStand(hitEntity) -> {
                    // Hit our own ArmorStand follower — ignore, no damage.
                    // Don't even unregister; the snowball might still hit
                    // something else in the same tick.
                    return Resolution.HitTrackingArmorStand
                }
                hitEntity is Player && hitEntity.uniqueId == data.throwerId -> {
                    // Shooter hit themselves (rare but possible with weird
                    // bounce physics). No damage to self.
                    Resolution.HitShooterSelf
                }
                hitEntity is Player -> {
                    // Player hit — go through the damage pipeline.
                    val result = damageService.resolveHit(data, hitEntity)
                    Resolution.HitPlayer(result)
                }
                hitEntity is ArmorStand -> {
                    // Some other ArmorStand (not our follower) — ignore.
                    Resolution.HitNonPlayerEntity
                }
                else -> {
                    // Mob or other entity. Configurable: by default, no
                    // custom combat applies to mobs. When enabled in config,
                    // schedule the vanilla damage on the next tick so we
                    // don't depend on the Entity#damage method being
                    // declared on the Spigot runtime Entity interface
                    // (the method exists in the API jar but Spigot 1.21's
                    // runtime classpath may not expose it consistently).
                    if (gameConfig.snowball.damageMobs && hitEntity is org.bukkit.entity.LivingEntity) {
                        val mob = hitEntity as org.bukkit.entity.LivingEntity
                        val amount = gameConfig.snowball.mobDamage
                        val projRef = projectile
                        plugin.server.scheduler.runTask(plugin, Runnable {
                            mob.damage(amount, projRef)
                        })
                    }
                    Resolution.HitNonPlayerEntity
                }
            }

            // 5. Apply post-hit effects based on the resolution.
            when (resolution) {
                is Resolution.HitPlayer -> handlePlayerHitOutcome(projectile, hitEntity as Player, data, resolution.result, match)
                is Resolution.HitShooterSelf -> {
                    // No feedback; just cleanup.
                }
                is Resolution.HitBlock -> {
                    // Optional: small splash particle when hitting a block.
                    if (gameConfig.effects.waterParticlesEnabled) {
                        spawnWaterParticles(projectile.location)
                    }
                }
                else -> { /* no-op for other resolutions */ }
            }

            // 6. Cleanup (always, idempotent).
            tracker.unregister(projectileId)

            return resolution
        } catch (e: Exception) {
            // Defensive — never let an exception in collision resolution
            // propagate to the Bukkit event dispatcher, which would log a
            // stack trace and potentially cancel the event.
            plugin.logger.warning("[Snowball] Collision resolution failed for $projectileId: ${e.javaClass.simpleName}: ${e.message}")
            tracker.unregister(projectileId)
            return Resolution.NotTracked
        }
    }

    /**
     * Apply knockback, effects, feedback, statistics, and elimination check
     * for a successful player-hit resolution.
     */
    private fun handlePlayerHitOutcome(
        projectile: Projectile,
        victim: Player,
        data: ProjectileData,
        result: DamageService.DamageResult,
        match: Match,
    ) {
        when (result.outcome) {
            DamageService.DamageResult.Outcome.ENEMY_HIT -> {
                // Apply knockback (push victim away from the projectile's
                // source direction).
                val fromLoc = projectile.location
                knockbackService.apply(fromLoc, victim)

                // Play hit effects (particles + sound).
                playHitEffects(victim.location)

                // Send combat feedback.
                val shooter = plugin.server.getPlayer(data.throwerId)
                if (shooter != null && shooter.isOnline) {
                    feedbackService.showEnemyHit(
                        shooter = shooter,
                        victim = victim,
                        damage = result.damageDealt,
                        victimHpAfter = result.resultingHp,
                        combo = result.comboAfterHit,
                    )
                }

                // Update HUDs.
                plugin.registry.messageService.sendActionBar(victim, "hud.actionbar.damaged",
                    "hp" to result.resultingHp, "damage" to result.damageDealt)

                // Record statistics (async, non-blocking).
                if (gameConfig.statistics.enabled) {
                    statisticsService.recordHitAsync(data.throwerId, result.damageDealt)
                }

                // Elimination check.
                if (result.victimEliminated) {
                    if (gameConfig.statistics.enabled) {
                        statisticsService.recordEliminationAsync(data.throwerId, victim.uniqueId)
                    }
                    feedbackService.showElimination(victim)
                    // Defer the actual match-state transition to MatchManager.
                    matchManager.eliminate(victim, match)
                }
            }
            DamageService.DamageResult.Outcome.FRIENDLY_FIRE -> {
                // No damage, no knockback. Just friendly-fire feedback.
                val shooter = plugin.server.getPlayer(data.throwerId)
                if (shooter != null && shooter.isOnline) {
                    feedbackService.showFriendlyFire(shooter, victim, data.throwerTeam)
                }
            }
            DamageService.DamageResult.Outcome.VICTIM_ELIMINATED_ALREADY,
            DamageService.DamageResult.Outcome.SHOOTER_ELIMINATED,
            DamageService.DamageResult.Outcome.VICTIM_NOT_PARTICIPANT -> {
                // Silent — these are normal mid-game transitions.
                plugin.logger.fine("[Snowball] Hit rejected: ${result.outcome}")
            }
        }
    }

    private fun playHitEffects(location: Location) {
        if (gameConfig.effects.waterParticlesEnabled) {
            location.world?.spawnParticle(
                Particle.CLOUD,
                location.clone().add(0.0, 1.0, 0.0),
                gameConfig.effects.particleCount.coerceIn(1, 200),
                0.3, 0.5, 0.3, 0.05,
            )
            location.world?.spawnParticle(
                Particle.FALLING_WATER,
                location.clone().add(0.0, 1.0, 0.0),
                gameConfig.effects.particleCount.coerceIn(1, 200),
                0.4, 0.6, 0.4, 0.05,
            )
        }
        if (gameConfig.effects.splashSoundEnabled) {
            location.world?.playSound(location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0f, 1.2f)
        }
    }

    private fun spawnWaterParticles(location: Location) {
        location.world?.spawnParticle(
            Particle.CLOUD,
            location,
            gameConfig.effects.particleCount.coerceIn(1, 100),
            0.2, 0.2, 0.2, 0.05,
        )
    }
}
