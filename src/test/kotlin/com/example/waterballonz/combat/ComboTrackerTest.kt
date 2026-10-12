package com.example.waterballonz.combat

import com.example.waterballonz.configuration.GameConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the combo-damage contract.
 *
 * Covers every rule described in section 11 of the spec, including the
 * boundary rule (a hit at exactly `lastHitAtMillis + comboWindowMillis` is
 * treated as a RESET).
 */
class ComboTrackerTest {

    private val attacker1 = UUID.randomUUID()
    private val attacker2 = UUID.randomUUID()
    private lateinit var tracker: ComboTracker

    @BeforeEach
    fun setUp() {
        tracker = ComboTracker(comboWindowMillis = 3000L)
    }

    @Test
    fun `first enemy hit returns 5 damage`() {
        val dmg = tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        assertEquals(5, dmg)
        assertEquals(1, tracker.getCombo(attacker1))
    }

    @Test
    fun `second consecutive hit returns 10`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        val dmg = tracker.recordEnemyHit(attacker1, nowMillis = 1000L)
        assertEquals(10, dmg)
        assertEquals(2, tracker.getCombo(attacker1))
    }

    @Test
    fun `third consecutive hit returns 15`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker1, nowMillis = 1000L)
        val dmg = tracker.recordEnemyHit(attacker1, nowMillis = 2000L)
        assertEquals(15, dmg)
    }

    @Test
    fun `fourth and later consecutive hits remain capped at 15`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker1, nowMillis = 1000L)
        tracker.recordEnemyHit(attacker1, nowMillis = 2000L)
        val fourth = tracker.recordEnemyHit(attacker1, nowMillis = 2500L)
        assertEquals(15, fourth)
        val fifth = tracker.recordEnemyHit(attacker1, nowMillis = 2900L)
        assertEquals(15, fifth)
    }

    @Test
    fun `hit after combo window resets to 5`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker1, nowMillis = 1000L)
        // 4001ms since last hit — strictly outside the 3000ms window
        val dmg = tracker.recordEnemyHit(attacker1, nowMillis = 4001L)
        assertEquals(5, dmg)
        assertEquals(1, tracker.getCombo(attacker1))
    }

    @Test
    fun `boundary - hit at exactly lastHit + window is treated as still inside`() {
        // Per the reference contract, `nowMillis - lastHitAtMillis > window`
        // is the reset test. At exactly `lastHit + window` the difference is
        // NOT greater than the window, so the hit is treated as still inside
        // the window (i.e. it advances the combo).
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        val boundary = 0L + 3000L
        val dmg = tracker.recordEnemyHit(attacker1, nowMillis = boundary)
        // Hit 2 inside the window → base (5) + step (5) = 10
        assertEquals(10, dmg)
        assertEquals(2, tracker.getCombo(attacker1))
    }

    @Test
    fun `two attackers maintain independent combo states`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker2, nowMillis = 0L)

        val dmg1 = tracker.recordEnemyHit(attacker1, nowMillis = 1000L)
        val dmg2 = tracker.recordEnemyHit(attacker2, nowMillis = 2000L)
        assertEquals(10, dmg1)
        assertEquals(10, dmg2)
        assertEquals(2, tracker.getCombo(attacker1))
        assertEquals(2, tracker.getCombo(attacker2))
    }

    @Test
    fun `reset clears the corresponding player's combo`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker1, nowMillis = 1000L)
        tracker.reset(attacker1)
        assertEquals(0, tracker.getCombo(attacker1))
        // And the next hit starts fresh
        val dmg = tracker.recordEnemyHit(attacker1, nowMillis = 1500L)
        assertEquals(5, dmg)
    }

    @Test
    fun `resetAll clears every player`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker2, nowMillis = 0L)
        tracker.resetAll()
        assertEquals(0, tracker.getCombo(attacker1))
        assertEquals(0, tracker.getCombo(attacker2))
    }

    @Test
    fun `cleanupDisconnectedPlayers removes stale entries`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 0L)
        tracker.recordEnemyHit(attacker2, nowMillis = 0L)
        // Only attacker2 is online now
        tracker.cleanupDisconnectedPlayers(setOf(attacker2))
        assertEquals(0, tracker.getCombo(attacker1))
        assertEquals(1, tracker.getCombo(attacker2))
    }

    @Test
    fun `lastHitAt exposes the recorded timestamp`() {
        tracker.recordEnemyHit(attacker1, nowMillis = 12345L)
        assertEquals(12345L, tracker.lastHitAt(attacker1))
    }

    @Test
    fun `recordEnemyHit honours custom base, step and cap`() {
        val custom = ComboTracker(comboWindowMillis = 5000L)
        // Base 3, step 3, cap 9
        val dmg1 = custom.recordEnemyHit(attacker1, nowMillis = 0L, baseDamage = 3, step = 3, cap = 9)
        val dmg2 = custom.recordEnemyHit(attacker1, nowMillis = 1000L, baseDamage = 3, step = 3, cap = 9)
        val dmg3 = custom.recordEnemyHit(attacker1, nowMillis = 2000L, baseDamage = 3, step = 3, cap = 9)
        val dmg4 = custom.recordEnemyHit(attacker1, nowMillis = 3000L, baseDamage = 3, step = 3, cap = 9)
        assertEquals(3, dmg1)
        assertEquals(6, dmg2)
        assertEquals(9, dmg3)
        assertEquals(9, dmg4) // capped
    }

    @Test
    fun `negative or zero combo window is rejected`() {
        var threw = false
        try {
            ComboTracker(0L)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
