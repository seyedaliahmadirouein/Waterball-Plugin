package com.example.waterballonz.persistence

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.team.Team
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Public statistics API used by commands, placeholders, and the match-end
 * pipeline.
 *
 * Backends:
 *  - SQLITE (default) — see [SqliteStatisticsBackend]
 *  - NONE               — no-op, returns zeros for every read
 *
 * The actual backend is selected at startup from `statistics.backend`.
 */
class StatisticsService(
    private val plugin: WaterBallonZPlugin,
    private val config: GameConfig,
) {
    data class PlayerStatistics(
        val playerId: UUID,
        val matchesPlayed: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val draws: Int = 0,
        val eliminations: Int = 0,
        val hits: Int = 0,
        val shots: Int = 0,
        val highestCombo: Int = 0,
        val damageDealt: Int = 0,
        val redWins: Int = 0,
        val blueWins: Int = 0,
    ) {
        val accuracy: Double get() = if (shots == 0) 0.0 else (hits.toDouble() / shots) * 100.0
    }

    private val inMemoryCache: MutableMap<UUID, PlayerStatistics> = ConcurrentHashMap()

    private var backend: SqliteStatisticsBackend? = null

    fun init() {
        if (!config.statistics.enabled) {
            plugin.logger.info("[WaterBallonZ] Statistics disabled by config.")
            return
        }
        when (config.statistics.backend.uppercase()) {
            "SQLITE" -> {
                val sqlite = SqliteStatisticsBackend(plugin)
                sqlite.open()
                backend = sqlite
            }
            else -> {
                plugin.logger.warning("[WaterBallonZ] Unknown statistics backend '${config.statistics.backend}' — disabled.")
            }
        }
    }

    fun close() {
        backend?.close()
        backend = null
    }

    // -- Writes (always async) --

    fun recordHitAsync(playerId: UUID, damage: Int) {
        backend?.recordHitAsync(playerId, damage)
    }

    fun recordShotAsync(playerId: UUID) {
        backend?.recordShotAsync(playerId)
    }

    fun recordEliminationAsync(shooterId: UUID, victimId: UUID) {
        backend?.recordEliminationAsync(shooterId, victimId)
    }

    fun recordMatchResultAsync(playerId: UUID, won: Boolean, drawn: Boolean, team: Team?) {
        backend?.recordMatchResultAsync(playerId, won, drawn, team?.id)
    }

    fun updateHighestComboAsync(playerId: UUID, combo: Int) {
        backend?.updateHighestComboAsync(playerId, combo)
    }

    // -- Reads (synchronous) --

    fun fetchStats(playerId: UUID): PlayerStatistics {
        backend?.let { return it.fetchStats(playerId) }
        return inMemoryCache[playerId] ?: PlayerStatistics(playerId)
    }

    fun resetAll() {
        backend?.resetAll()
        inMemoryCache.clear()
    }

    val isEnabled: Boolean get() = config.statistics.enabled && backend != null
}
