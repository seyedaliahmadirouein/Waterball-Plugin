package com.example.waterballonz.placeholders

import com.example.waterballonz.arena.ArenaState
import com.example.waterballonz.bootstrap.ServiceRegistry
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * PlaceholderAPI integration.
 *
 * Placeholders (prefix `waterballonz_`):
 *  - %waterballonz_arena%
 *  - %waterballonz_team%
 *  - %waterballonz_hp%
 *  - %waterballonz_ammo%
 *  - %waterballonz_combo%
 *  - %waterballonz_match_state%
 *  - %waterballonz_time_remaining%
 *  - %waterballonz_alive_red%
 *  - %waterballonz_alive_blue%
 *  - %waterballonz_wins%
 *  - %waterballonz_losses%
 *  - %waterballonz_kills% (eliminations)
 *  - %waterballonz_hits%
 *  - %waterballonz_shots%
 *
 * Each placeholder returns "-" when the player is not in a match.
 */
class PlaceholderHook(private val registry: ServiceRegistry) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "waterballonz"
    override fun getAuthor(): String = "seyedaliahmadirouein"
    override fun getVersion(): String = "1.0.0"
    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String {
        if (player == null) return "-"
        val session = registry.sessionService.sessionOf(player.uniqueId)
        val match = session?.let { registry.matchManager.matchOf(it.arenaId) }

        return when (params.lowercase()) {
            "arena" -> session?.arenaId ?: "-"
            "team" -> session?.participant?.team?.id ?: "-"
            "hp" -> session?.participant?.hp?.toString() ?: "-"
            "ammo" -> session?.participant?.ammo?.toString() ?: "-"
            "combo" -> session?.participant?.comboHits?.toString() ?: "-"
            "match_state" -> match?.state?.name?.lowercase() ?: "-"
            "time_remaining" -> {
                if (match == null || match.state != ArenaState.IN_GAME) "-"
                else {
                    val remaining = match.definition.maximumDurationSeconds - match.matchSecondsElapsed
                    if (remaining < 0) "0" else "${remaining}s"
                }
            }
            "alive_red" -> match?.let {
                registry.sessionService.aliveParticipantsIn(it.arenaId())
                    .count { p -> p.team == com.example.waterballonz.team.Team.RED }
                    .toString()
            } ?: "-"
            "alive_blue" -> match?.let {
                registry.sessionService.aliveParticipantsIn(it.arenaId())
                    .count { p -> p.team == com.example.waterballonz.team.Team.BLUE }
                    .toString()
            } ?: "-"
            "wins", "losses", "kills", "hits", "shots", "draws", "damage" -> {
                val stats = registry.statisticsService.fetchStats(player.uniqueId)
                when (params.lowercase()) {
                    "wins" -> stats.wins
                    "losses" -> stats.losses
                    "kills" -> stats.eliminations
                    "hits" -> stats.hits
                    "shots" -> stats.shots
                    "draws" -> stats.draws
                    "damage" -> stats.damageDealt
                    else -> 0
                }.toString()
            }
            else -> "-"
        }
    }
}
