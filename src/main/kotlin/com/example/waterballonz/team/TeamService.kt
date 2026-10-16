package com.example.waterballonz.team

import com.example.waterballonz.player.PlayerSessionService
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Centralized team lookup service.
 *
 * Per spec section 19 — "Team abstraction": "Do not hardcode
 * `if (team.equals("RED"))` throughout the code. The team lookup should be
 * centralized. Example: `Team team = teamService.getTeam(player);`"
 *
 * This service is the single source of truth for "which team is this player
 * on?". Combat services, listeners, placeholders, etc. should ALL query
 * team membership through this service rather than re-querying the
 * [TeamBalancer] or [PlayerSessionService] directly.
 *
 * Returns null for players who aren't currently in an active WaterBallonZ
 * session (e.g. spectators who haven't been assigned a team, or players
 * between matches).
 */
class TeamService(
    private val sessionService: PlayerSessionService,
    private val teamBalancer: TeamBalancer,
) {

    /**
     * Look up the team of [player].
     *
     * @return the [Team], or null if the player is not currently in an
     *   active session.
     */
    fun teamOf(player: Player): Team? = teamOf(player.uniqueId)

    /**
     * Look up the team of the player with the given [playerId].
     */
    fun teamOf(playerId: UUID): Team? =
        sessionService.sessionOf(playerId)?.participant?.team

    /**
     * Are [shooter] and [victim] on the same team?
     *
     * Returns false if either player isn't in a session.
     */
    fun areSameTeam(shooter: Player, victim: Player): Boolean {
        val shooterTeam = teamOf(shooter) ?: return false
        val victimTeam = teamOf(victim) ?: return false
        return shooterTeam == victimTeam
    }
}
