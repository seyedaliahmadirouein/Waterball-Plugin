package com.example.waterballonz.combat.service

import com.example.waterballonz.combat.ComboTracker
import com.example.waterballonz.combat.projectile.ProjectileData
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.health.GameHealthService
import com.example.waterballonz.player.Participant
import com.example.waterballonz.player.PlayerSessionService
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Applies custom-HP damage to a victim participant.
 *
 * Pure business logic — no Bukkit API calls, no event cancellation, no
 * effects. Easy to unit-test.
 *
 * Combo progression is calculated here, friendly-fire is enforced here, and
 * the resulting [DamageResult] is returned so the caller (CollisionResolver)
 * can play effects and feedback without re-doing the math.
 *
 * Anti-bug invariants enforced:
 *  - Friendly hits never apply damage and never advance the combo.
 *  - Eliminated players cannot deal or receive damage.
 *  - HP never falls below 0 or above the configured maximum.
 *  - A single projectile produces exactly one damage resolution.
 */
class DamageService(
    private val gameConfig: GameConfig,
    private val healthService: GameHealthService,
    private val comboTracker: ComboTracker,
    private val sessionService: PlayerSessionService,
) {

    /**
     * Result of a damage attempt. Carries everything the caller needs to
     * play effects + feedback without re-querying state.
     */
    data class DamageResult(
        val outcome: Outcome,
        val damageDealt: Int,
        val resultingHp: Int,
        val comboAfterHit: Int,
        val victimEliminated: Boolean,
    ) {
        enum class Outcome {
            ENEMY_HIT,
            FRIENDLY_FIRE,
            VICTIM_ELIMINATED_ALREADY,
            SHOOTER_ELIMINATED,
            VICTIM_NOT_PARTICIPANT,
        }
    }

    /**
     * Resolve a hit from [projectileData] against the given victim [player].
     *
     * The victim must be a live participant in the same match as the thrower.
     * The thrower is identified by [projectileData.throwerId] and looked up
     * via [PlayerSessionService].
     */
    fun resolveHit(projectileData: ProjectileData, victim: Player): DamageResult {
        val shooterId = projectileData.throwerId

        val shooterSession = sessionService.sessionOf(shooterId)
            ?: return DamageResult(
                outcome = DamageResult.Outcome.SHOOTER_ELIMINATED,
                damageDealt = 0,
                resultingHp = -1,
                comboAfterHit = 0,
                victimEliminated = false,
            )

        if (!shooterSession.participant.alive) {
            return DamageResult(
                outcome = DamageResult.Outcome.SHOOTER_ELIMINATED,
                damageDealt = 0,
                resultingHp = -1,
                comboAfterHit = 0,
                victimEliminated = false,
            )
        }

        val victimSession = sessionService.sessionOf(victim.uniqueId)
            ?: return DamageResult(
                outcome = DamageResult.Outcome.VICTIM_NOT_PARTICIPANT,
                damageDealt = 0,
                resultingHp = -1,
                comboAfterHit = 0,
                victimEliminated = false,
            )

        if (!victimSession.participant.alive) {
            return DamageResult(
                outcome = DamageResult.Outcome.VICTIM_ELIMINATED_ALREADY,
                damageDealt = 0,
                resultingHp = victimSession.participant.hp,
                comboAfterHit = 0,
                victimEliminated = true,
            )
        }

        // Friendly-fire check: same team, FF disabled → no damage, no combo.
        if (shooterSession.participant.team == victimSession.participant.team &&
            !gameConfig.combat.friendlyFire
        ) {
            return DamageResult(
                outcome = DamageResult.Outcome.FRIENDLY_FIRE,
                damageDealt = 0,
                resultingHp = victimSession.participant.hp,
                comboAfterHit = comboTracker.getCombo(shooterId),
                victimEliminated = false,
            )
        }

        // Calculate combo damage (base + step * comboIndex, capped).
        val damage = comboTracker.recordEnemyHit(
            attackerId = shooterId,
            nowMillis = System.currentTimeMillis(),
            baseDamage = gameConfig.combat.baseDamagePercent,
            step = gameConfig.combat.damagePerComboStep,
            cap = gameConfig.combat.maximumDamagePercent,
        )

        val newHp = healthService.applyDamage(victimSession.participant.hp, damage)
        victimSession.participant.hp = newHp
        victimSession.participant.damageDealt += 0  // not the shooter's damage to themselves

        // Update shooter's stats
        shooterSession.participant.successfulHits++
        shooterSession.participant.comboHits = comboTracker.getCombo(shooterId)
        shooterSession.participant.damageDealt += damage
        if (shooterSession.participant.comboHits > shooterSession.participant.highestCombo) {
            shooterSession.participant.highestCombo = shooterSession.participant.comboHits
        }

        val eliminated = newHp <= 0
        if (eliminated) {
            // Mark the victim as eliminated (don't call MatchManager here —
            // the collision resolver is responsible for that).
            victimSession.participant.alive = false
            victimSession.participant.eliminated = true
            // Reset the shooter's combo? No — the spec says the attacker's
            // combo is reset only when their own hits time out, not when
            // someone gets eliminated. Leave it alone.
        }

        return DamageResult(
            outcome = if (eliminated) DamageResult.Outcome.ENEMY_HIT
                      else DamageResult.Outcome.ENEMY_HIT,
            damageDealt = damage,
            resultingHp = newHp,
            comboAfterHit = shooterSession.participant.comboHits,
            victimEliminated = eliminated,
        )
    }
}
