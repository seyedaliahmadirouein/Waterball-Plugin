package com.example.waterballonz.team

import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.health.GameHealthService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the team balancer.
 *
 * Verifies the balancing invariant: |Red| - |Blue| <= 1.
 */
class TeamBalancerTest {

    private lateinit var balancer: TeamBalancer
    private val config = GameConfig(
        general = GameConfig.GeneralConfig(
            prefix = "", debug = false, autoStart = true,
            autoBalanceTeams = true,
            restorePlayerState = true,
            returnToLobbyAfterMatch = true,
            lobby = GameConfig.LobbyConfig(true, true, true),
        ),
        game = GameConfig.GameRulesConfig(2, 16, 3, 600, 5, false),
        health = GameConfig.HealthConfig(100, 100, true, 120, 5),
        waterball = GameConfig.WaterBallConfig(5, 1, 64, "SNOWBALL", "Ball", emptyList(), 0, false),
        combat = GameConfig.CombatConfig(5, 15, 5, 3, false),
        effects = GameConfig.EffectsConfig(true, true, true, true, 20, true),
        hud = GameConfig.HudConfig(true, 10, true, false),
        disconnect = GameConfig.DisconnectConfig(true, false, 30),
        statistics = GameConfig.StatisticsConfig(true, "SQLITE"),
        integrations = GameConfig.IntegrationsConfig(true),
    )

    @BeforeEach
    fun setUp() {
        balancer = TeamBalancer(config)
    }

    @Test
    fun `2 players are split 1-1`() {
        val p1 = UUID.randomUUID()
        val p2 = UUID.randomUUID()
        val t1 = balancer.assign(p1, maxPerTeam = 8)
        val t2 = balancer.assign(p2, maxPerTeam = 8)
        assertNotNull(t1)
        assertNotNull(t2)
        assertTrue(t1!!.opposite() == t2)
        assertEquals(1, balancer.count(Team.RED) + balancer.count(Team.BLUE) - 1, "Exactly one of the two players is on the same team as the other's opposite")
        assertEquals(1, balancer.count(Team.RED))
        assertEquals(1, balancer.count(Team.BLUE))
    }

    @Test
    fun `4 players are split 2-2`() {
        val players = (1..4).map { UUID.randomUUID() }
        players.forEach { balancer.assign(it, maxPerTeam = 4) }
        assertEquals(2, balancer.count(Team.RED))
        assertEquals(2, balancer.count(Team.BLUE))
        assertTrue(balancer.isBalanced())
    }

    @Test
    fun `5 players are split 3-2`() {
        val players = (1..5).map { UUID.randomUUID() }
        players.forEach { balancer.assign(it, maxPerTeam = 5) }
        val r = balancer.count(Team.RED)
        val b = balancer.count(Team.BLUE)
        assertEquals(5, r + b)
        assertTrue(kotlin.math.abs(r - b) <= 1, "Diff is $r vs $b")
    }

    @Test
    fun `3 players are split 2-1`() {
        val players = (1..3).map { UUID.randomUUID() }
        players.forEach { balancer.assign(it, maxPerTeam = 3) }
        assertEquals(3, balancer.count(Team.RED) + balancer.count(Team.BLUE))
        assertTrue(kotlin.math.abs(balancer.count(Team.RED) - balancer.count(Team.BLUE)) <= 1)
    }

    @Test
    fun `maximum capacity is enforced`() {
        val players = (1..10).map { UUID.randomUUID() }
        // Allow 4 per team
        players.forEach { balancer.assign(it, maxPerTeam = 4) }
        // Should have only accepted 8
        assertEquals(4, balancer.count(Team.RED))
        assertEquals(4, balancer.count(Team.BLUE))
        // The 9th and 10th players must be rejected
        val extra = UUID.randomUUID()
        val result = balancer.assign(extra, maxPerTeam = 4)
        assertNull(result)
    }

    @Test
    fun `no duplicate membership`() {
        val p1 = UUID.randomUUID()
        val first = balancer.assign(p1, maxPerTeam = 8)
        val second = balancer.assign(p1, maxPerTeam = 8)
        assertEquals(first, second)
    }

    @Test
    fun `teamOf returns the team after assignment`() {
        val p1 = UUID.randomUUID()
        val team = balancer.assign(p1, maxPerTeam = 8)!!
        assertEquals(team, balancer.teamOf(p1))
        assertTrue(balancer.isAssigned(p1))
    }

    @Test
    fun `remove returns the team the player was on`() {
        val p1 = UUID.randomUUID()
        val team = balancer.assign(p1, maxPerTeam = 8)!!
        val removedTeam = balancer.remove(p1)
        assertEquals(team, removedTeam)
        assertEquals(0, balancer.count(team))
    }

    @Test
    fun `clear empties both teams`() {
        val players = (1..4).map { UUID.randomUUID() }
        players.forEach { balancer.assign(it, maxPerTeam = 4) }
        balancer.clear()
        assertEquals(0, balancer.count(Team.RED))
        assertEquals(0, balancer.count(Team.BLUE))
    }
}
