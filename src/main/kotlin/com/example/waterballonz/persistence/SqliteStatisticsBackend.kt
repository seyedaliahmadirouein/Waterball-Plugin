package com.example.waterballonz.persistence

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.persistence.StatisticsService.PlayerStatistics
import org.bukkit.Bukkit
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite-backed statistics storage.
 *
 * All write operations are non-blocking from the perspective of the calling
 * thread: they are dispatched to a single-writer background executor so that
 * combat logic on the main server thread never waits on a JDBC call.
 *
 * Schema:
 *
 *   CREATE TABLE IF NOT EXISTS player_stats (
 *     player_uuid      TEXT PRIMARY KEY,
 *     matches_played   INTEGER NOT NULL DEFAULT 0,
 *     wins             INTEGER NOT NULL DEFAULT 0,
 *     losses           INTEGER NOT NULL DEFAULT 0,
 *     draws            INTEGER NOT NULL DEFAULT 0,
 *     eliminations     INTEGER NOT NULL DEFAULT 0,
 *     hits             INTEGER NOT NULL DEFAULT 0,
 *     shots            INTEGER NOT NULL DEFAULT 0,
 *     highest_combo    INTEGER NOT NULL DEFAULT 0,
 *     damage_dealt     INTEGER NOT NULL DEFAULT 0
 *   );
 *
 * The schema is forward-compatible — additional columns can be added with
 * ALTER TABLE in [applyMigrations] without breaking older rows.
 */
class SqliteStatisticsBackend(
    private val plugin: WaterBallonZPlugin,
) : AutoCloseable {

    @Volatile
    private var connection: Connection? = null

    private val writerQueue = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "WaterBallonZ-stat-writer").apply { isDaemon = true }
    }

    @Synchronized
    fun open() {
        if (connection != null) return
        val dataDir = plugin.dataFolder.apply { if (!exists()) mkdirs() }
        val dbFile = java.io.File(dataDir, "statistics.db")
        try {
            Class.forName("org.sqlite.JDBC")
            connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            applyMigrations(connection!!)
            plugin.logger.info("[WaterBallonZ] SQLite statistics opened at ${dbFile.absolutePath}")
        } catch (e: ClassNotFoundException) {
            plugin.logger.severe("[WaterBallonZ] SQLite JDBC driver not available on the classpath: ${e.message}")
            throw e
        } catch (e: SQLException) {
            plugin.logger.severe("[WaterBallonZ] SQLite open failed: ${e.message}")
            throw e
        } catch (e: UnsatisfiedLinkError) {
            // The SQLite .so/.dll/.dylib couldn't be linked — typically because
            // the package was relocated (JNI function names mismatch) or the
            // host OS/architecture isn't bundled. Re-throw so StatisticsService
            // catches it at the top level and disables stats gracefully.
            plugin.logger.severe("[WaterBallonZ] SQLite native library could not be loaded: ${e.message}")
            throw e
        } catch (e: NoClassDefFoundError) {
            plugin.logger.severe("[WaterBallonZ] SQLite class not found: ${e.message}")
            throw e
        }
    }

    @Synchronized
    override fun close() {
        try {
            writerQueue.shutdown()
            if (!writerQueue.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                writerQueue.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        connection?.use { it.close() }
        connection = null
    }

    private fun applyMigrations(conn: Connection) {
        conn.createStatement().use { stmt: Statement ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_stats (
                  player_uuid      TEXT PRIMARY KEY,
                  matches_played   INTEGER NOT NULL DEFAULT 0,
                  wins              INTEGER NOT NULL DEFAULT 0,
                  losses            INTEGER NOT NULL DEFAULT 0,
                  draws             INTEGER NOT NULL DEFAULT 0,
                  eliminations       INTEGER NOT NULL DEFAULT 0,
                  hits              INTEGER NOT NULL DEFAULT 0,
                  shots             INTEGER NOT NULL DEFAULT 0,
                  highest_combo     INTEGER NOT NULL DEFAULT 0,
                  damage_dealt      INTEGER NOT NULL DEFAULT 0,
                  red_wins          INTEGER NOT NULL DEFAULT 0,
                  blue_wins         INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            // Example forward migration: add a column if missing.
            addColumnIfMissing(stmt, "player_stats", "red_wins", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(stmt, "player_stats", "blue_wins", "INTEGER NOT NULL DEFAULT 0")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_stats_wins ON player_stats(wins) DESC")
        }
    }

    private fun addColumnIfMissing(stmt: Statement, table: String, column: String, definition: String) {
        val rs: ResultSet = try {
            stmt.executeQuery("PRAGMA table_info($table)")
        } catch (e: SQLException) { return }
        rs.use {
            val names = mutableListOf<String>()
            while (it.next()) names.add(it.getString("name"))
            if (column !in names) {
                try {
                    stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
                } catch (e: SQLException) {
                    plugin.logger.warning("[WaterBallonZ] Could not add column $column to $table: ${e.message}")
                }
            }
        }
    }

    fun recordHitAsync(playerUuid: UUID, damage: Int) {
        submitAsync { conn ->
            incrementColumns(
                conn, playerUuid,
                hits = 1, damage_dealt = damage
            )
        }
    }

    fun recordShotAsync(playerUuid: UUID) {
        submitAsync { conn -> incrementColumns(conn, playerUuid, shots = 1) }
    }

    fun recordEliminationAsync(shooterId: UUID, victimId: UUID) {
        submitAsync { conn ->
            incrementColumns(conn, shooterId, eliminations = 1)
        }
    }

    fun recordMatchResultAsync(
        playerId: UUID,
        won: Boolean,
        drawn: Boolean,
        teamId: String?,
    ) {
        submitAsync { conn ->
            val wins = if (won && !drawn) 1 else 0
            val losses = if (!won && !drawn) 1 else 0
            val draws = if (drawn) 1 else 0
            val redWins = if (won && teamId == "red") 1 else 0
            val blueWins = if (won && teamId == "blue") 1 else 0
            incrementColumns(
                conn, playerId,
                matches_played = 1,
                wins = wins,
                losses = losses,
                draws = draws,
                red_wins = redWins,
                blue_wins = blueWins,
            )
        }
    }

    fun updateHighestComboAsync(playerId: UUID, combo: Int) {
        submitAsync { conn ->
            conn.prepareStatement(
                "UPDATE player_stats SET highest_combo = MAX(highest_combo, ?) WHERE player_uuid = ?"
            ).use { ps: PreparedStatement ->
                ps.setInt(1, combo)
                ps.setString(2, playerId.toString())
                ps.executeUpdate()
            }
        }
    }

    /**
     * Synchronous read for placeholder/command consumers. Returns an empty
     * [PlayerStatistics] if the player has no recorded data.
     */
    @Synchronized
    fun fetchStats(playerId: UUID): PlayerStatistics {
        val conn = connection ?: return PlayerStatistics(playerId)
        conn.prepareStatement(
            "SELECT matches_played, wins, losses, draws, eliminations, hits, shots, highest_combo, damage_dealt, red_wins, blue_wins FROM player_stats WHERE player_uuid = ?"
        ).use { ps ->
            ps.setString(1, playerId.toString())
            val rs = ps.executeQuery()
            return if (rs.next()) {
                PlayerStatistics(
                    playerId = playerId,
                    matchesPlayed = rs.getInt("matches_played"),
                    wins = rs.getInt("wins"),
                    losses = rs.getInt("losses"),
                    draws = rs.getInt("draws"),
                    eliminations = rs.getInt("eliminations"),
                    hits = rs.getInt("hits"),
                    shots = rs.getInt("shots"),
                    highestCombo = rs.getInt("highest_combo"),
                    damageDealt = rs.getInt("damage_dealt"),
                    redWins = rs.getInt("red_wins"),
                    blueWins = rs.getInt("blue_wins"),
                )
            } else PlayerStatistics(playerId)
        }
    }

    @Synchronized
    fun resetAll() {
        val conn = connection ?: return
        conn.createStatement().use { it.executeUpdate("DELETE FROM player_stats") }
    }

    private fun incrementColumns(
        conn: Connection,
        playerId: UUID,
        matches_played: Int = 0,
        wins: Int = 0,
        losses: Int = 0,
        draws: Int = 0,
        eliminations: Int = 0,
        hits: Int = 0,
        shots: Int = 0,
        damage_dealt: Int = 0,
        red_wins: Int = 0,
        blue_wins: Int = 0,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO player_stats (
              player_uuid, matches_played, wins, losses, draws,
              eliminations, hits, shots, highest_combo, damage_dealt,
              red_wins, blue_wins
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
              matches_played = matches_played + excluded.matches_played,
              wins = wins + excluded.wins,
              losses = losses + excluded.losses,
              draws = draws + excluded.draws,
              eliminations = eliminations + excluded.eliminations,
              hits = hits + excluded.hits,
              shots = shots + excluded.shots,
              damage_dealt = damage_dealt + excluded.damage_dealt,
              red_wins = red_wins + excluded.red_wins,
              blue_wins = blue_wins + excluded.blue_wins
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, playerId.toString())
            ps.setInt(2, matches_played)
            ps.setInt(3, wins)
            ps.setInt(4, losses)
            ps.setInt(5, draws)
            ps.setInt(6, eliminations)
            ps.setInt(7, hits)
            ps.setInt(8, shots)
            ps.setInt(9, damage_dealt)
            ps.setInt(10, red_wins)
            ps.setInt(11, blue_wins)
            ps.executeUpdate()
        }
    }

    private fun submitAsync(action: (Connection) -> Unit) {
        val connSnapshot = connection ?: return
        writerQueue.execute {
            try {
                action(connSnapshot)
            } catch (e: SQLException) {
                plugin.logger.warning("[WaterBallonZ] Statistics write failed: ${e.message}")
            }
        }
    }
}
