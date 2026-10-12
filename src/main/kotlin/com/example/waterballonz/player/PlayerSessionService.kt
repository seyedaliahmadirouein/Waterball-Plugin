package com.example.waterballonz.player

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.ArenaDefinition
import com.example.waterballonz.match.Match
import com.example.waterballonz.team.Team
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Per-participant runtime state inside a match.
 */
data class Participant(
    val playerId: UUID,
    val arenaId: String,
    val matchId: Long,
    var team: Team,
    var hp: Int,
    var ammo: Int,
    var alive: Boolean = true,
    var eliminated: Boolean = false,
    var comboHits: Int = 0,
    var highestCombo: Int = 0,
    var shotsFired: Int = 0,
    var successfulHits: Int = 0,
    var damageDealt: Int = 0,
    var joinTimeMillis: Long = System.currentTimeMillis(),
) {
    fun adjustHp(delta: Int): Int {
        hp = (hp + delta).coerceIn(0, 100)
        if (hp == 0) alive = false
        return hp
    }
}

/**
 * Tracks every WaterBallonZ player session on the server.
 *
 * A [PlayerSession] records which arena the player is in and which [Match]
 * they belong to. Listeners and commands consult this map to determine
 * whether an event applies to an active match.
 */
data class PlayerSession(
    val playerId: UUID,
    val arenaId: String,
    val matchId: Long,
    val participant: Participant,
    var preGameReturnLocation: org.bukkit.Location? = null,
    var movementLocked: Boolean = false,
    var isSpectator: Boolean = false,
)

class PlayerSessionService(private val plugin: WaterBallonZPlugin) {

    private val sessions: MutableMap<UUID, PlayerSession> = mutableMapOf()

    @Synchronized
    fun startSession(
        player: Player,
        arena: ArenaDefinition,
        match: Match,
        team: Team,
        hp: Int,
        ammo: Int,
    ): PlayerSession {
        // Refuse duplicate sessions — a player cannot join two arenas.
        if (sessions.containsKey(player.uniqueId)) {
            throw IllegalStateException("${player.name} already has an active WaterBallonZ session.")
        }
        val participant = Participant(
            playerId = player.uniqueId,
            arenaId = arena.id,
            matchId = match.matchId,
            team = team,
            hp = hp,
            ammo = ammo,
        )
        val session = PlayerSession(
            playerId = player.uniqueId,
            arenaId = arena.id,
            matchId = match.matchId,
            participant = participant,
        )
        sessions[player.uniqueId] = session
        return session
    }

    @Synchronized
    fun sessionOf(playerId: UUID): PlayerSession? = sessions[playerId]

    @Synchronized
    fun isParticipant(playerId: UUID): Boolean = sessions.containsKey(playerId)

    @Synchronized
    fun isAliveParticipant(playerId: UUID): Boolean =
        sessions[playerId]?.participant?.alive == true

    @Synchronized
    fun endSession(playerId: UUID): PlayerSession? = sessions.remove(playerId)

    @Synchronized
    fun participantsIn(arenaId: String): List<Participant> =
        sessions.values.filter { it.arenaId == arenaId }.map { it.participant }

    @Synchronized
    fun aliveParticipantsIn(arenaId: String): List<Participant> =
        sessions.values.filter { it.arenaId == arenaId && it.participant.alive }.map { it.participant }

    @Synchronized
    fun sessionsIn(arenaId: String): List<PlayerSession> =
        sessions.values.filter { it.arenaId == arenaId }

    @Synchronized
    fun allSessions(): List<PlayerSession> = sessions.values.toList()

    @Synchronized
    fun countIn(arenaId: String): Int = sessions.values.count { it.arenaId == arenaId }

    @Synchronized
    fun clearArena(arenaId: String) {
        sessions.keys.removeAll { key ->
            sessions[key]?.arenaId == arenaId
        }
    }
}
