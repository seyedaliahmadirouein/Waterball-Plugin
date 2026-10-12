package com.example.waterballonz.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the [ArenaState] state-machine contract.
 *
 * These tests verify that every documented transition is allowed and that
 * illegal transitions are rejected.
 */
class ArenaStateTest {

    @Test
    fun `DISABLED can transition to WAITING`() {
        assertTrue(ArenaState.DISABLED.canTransitionTo(ArenaState.WAITING))
    }

    @Test
    fun `WAITING can transition to DISABLED or STARTING`() {
        assertTrue(ArenaState.WAITING.canTransitionTo(ArenaState.DISABLED))
        assertTrue(ArenaState.WAITING.canTransitionTo(ArenaState.STARTING))
    }

    @Test
    fun `STARTING can transition to COUNTDOWN or back to WAITING`() {
        assertTrue(ArenaState.STARTING.canTransitionTo(ArenaState.COUNTDOWN))
        assertTrue(ArenaState.STARTING.canTransitionTo(ArenaState.WAITING))
        assertTrue(ArenaState.STARTING.canTransitionTo(ArenaState.DISABLED))
    }

    @Test
    fun `COUNTDOWN can transition to IN_GAME or back to WAITING`() {
        assertTrue(ArenaState.COUNTDOWN.canTransitionTo(ArenaState.IN_GAME))
        assertTrue(ArenaState.COUNTDOWN.canTransitionTo(ArenaState.WAITING))
    }

    @Test
    fun `IN_GAME can transition to ENDING`() {
        assertTrue(ArenaState.IN_GAME.canTransitionTo(ArenaState.ENDING))
    }

    @Test
    fun `ENDING can transition to RESETTING`() {
        assertTrue(ArenaState.ENDING.canTransitionTo(ArenaState.RESETTING))
    }

    @Test
    fun `RESETTING can transition to WAITING or DISABLED`() {
        assertTrue(ArenaState.RESETTING.canTransitionTo(ArenaState.WAITING))
        assertTrue(ArenaState.RESETTING.canTransitionTo(ArenaState.DISABLED))
    }

    @Test
    fun `illegal transitions are rejected`() {
        assertFalse(ArenaState.WAITING.canTransitionTo(ArenaState.IN_GAME))
        assertFalse(ArenaState.DISABLED.canTransitionTo(ArenaState.IN_GAME))
        assertFalse(ArenaState.COUNTDOWN.canTransitionTo(ArenaState.ENDING))
        assertFalse(ArenaState.IN_GAME.canTransitionTo(ArenaState.COUNTDOWN))
        assertFalse(ArenaState.ENDING.canTransitionTo(ArenaState.IN_GAME))
    }

    @Test
    fun `all disabled arenas can disable from any state`() {
        // Disabled transition target must be allowed from any state that goes through
        // a typical lifecycle (per the spec).
        for (state in listOf(ArenaState.WAITING, ArenaState.STARTING, ArenaState.COUNTDOWN, ArenaState.IN_GAME, ArenaState.RESETTING)) {
            assertTrue(state.canTransitionTo(ArenaState.DISABLED),
                "Expected $state -> DISABLED to be allowed")
        }
    }
}
