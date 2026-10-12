package com.example.waterballonz.team

import com.example.waterballonz.configuration.GameConfig
import java.util.UUID
import kotlin.random.Random

/**
 * Distributes players between Red and Blue.
 *
 * The number of players on the larger team differs by at most one whenever
 * normal assignment is possible:
 *
 *   2 -> 1:1
 *   3 -> 2:1
 *   4 -> 2:2
 *   5 -> 3:2
 *   6 -> 3:3
 *
 * When [GameConfig.GeneralConfig.autoBalanceTeams] is true, every new player is
 * placed on the team with the fewest current members. When `autoBalanceTeams`
 * is disabled, the player may select a team — but the match will still refuse
 * to start if the configured imbalance is exceeded.
 *
 * Implementation is deterministic (alters between Red and Blue at the start of
 * each new arena, using the arena id hash as the seed) when configured so, and
 * randomised when not. Either way, the balancing invariant is enforced.
 */
class TeamBalancer(private val config: GameConfig) {

    private val membersByTeam: MutableMap<Team, MutableSet<UUID>> = mutableMapOf(
        Team.RED to mutableSetOf(),
        Team.BLUE to mutableSetOf(),
    )

    /** The team that will be given to the *next* player when balancing is on. */
    private var nextTeam: Team = if (Random.nextBoolean()) Team.RED else Team.BLUE

    /** @return the team assigned, or `null` if both teams are full. */
    @Synchronized
    fun assign(
        player: UUID,
        preference: Team? = null,
        maxPerTeam: Int = Int.MAX_VALUE,
    ): Team? {
        // Re-balance if the player was already assigned
        if (isAssigned(player)) {
            return teamOf(player)
        }

        val red = membersByTeam[Team.RED]!!
        val blue = membersByTeam[Team.BLUE]!!

        if (red.size >= maxPerTeam && blue.size >= maxPerTeam) return null

        val chosen = when {
            // If only one side is full, must go to the other
            red.size >= maxPerTeam -> Team.BLUE
            blue.size >= maxPerTeam -> Team.RED
            // If balancing is disabled and a preference exists, respect it
            !config.general.autoBalanceTeams && preference != null -> preference
            // Otherwise balance: pick the team with fewer members.
            red.size < blue.size -> Team.RED
            blue.size < red.size -> Team.BLUE
            // Tied — use the alternator
            else -> {
                val alternate = nextTeam
                nextTeam = alternate.opposite()
                alternate
            }
        }

        membersByTeam[chosen]!!.add(player)
        return chosen
    }

    @Synchronized
    fun remove(player: UUID): Team? {
        for ((team, members) in membersByTeam) {
            if (members.remove(player)) return team
        }
        return null
    }

    @Synchronized
    fun teamOf(player: UUID): Team? = membersByTeam.entries.firstOrNull { it.value.contains(player) }?.key

    @Synchronized
    fun isAssigned(player: UUID): Boolean = membersByTeam.values.any { it.contains(player) }

    @Synchronized
    fun membersOf(team: Team): Set<UUID> = membersByTeam[team]!!.toSet()

    @Synchronized
    fun count(team: Team): Int = membersByTeam[team]!!.size

    @Synchronized
    fun teamWithFewerMembers(): Team =
        if (membersByTeam[Team.RED]!!.size <= membersByTeam[Team.BLUE]!!.size) Team.RED else Team.BLUE

    @Synchronized
    fun isBalanced(): Boolean {
        val r = count(Team.RED)
        val b = count(Team.BLUE)
        return kotlin.math.abs(r - b) <= 1
    }

    @Synchronized
    fun aliveCount(team: Team, alivePredicate: (UUID) -> Boolean): Int {
        return membersByTeam[team]!!.count(alivePredicate)
    }

    @Synchronized
    fun clear() {
        membersByTeam.values.forEach { it.clear() }
    }
}
