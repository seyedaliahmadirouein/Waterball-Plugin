package com.example.waterballonz.arena

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.repository.ArenaRepository
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.match.MatchManager
import org.bukkit.Location

/**
 * Public API for arena management.
 *
 * All mutations route through this service so that the [MatchManager] can be
 * notified, the repository is updated atomically, and validation runs before
 * state changes.
 */
class ArenaService(
    private val plugin: WaterBallonZPlugin,
    private val repository: ArenaRepository,
    private val validator: ArenaValidator,
) {
    private lateinit var matchManager: MatchManager

    fun attachMatchManager(manager: MatchManager) {
        matchManager = manager
    }

    fun arenaCount(): Int = repository.all().size

    fun allArenas(): List<ArenaDefinition> = repository.all()

    fun getArena(id: String): ArenaDefinition? = repository.get(id)

    fun arenaExists(id: String): Boolean = repository.exists(id)

    fun createArena(id: String): ArenaDefinition {
        require(id.matches(Regex("^[a-zA-Z0-9_-]{1,32}$"))) {
            "Arena id must be 1-32 alphanumeric/underscore/hyphen characters."
        }
        if (repository.exists(id)) {
            throw IllegalArgumentException("Arena '$id' already exists.")
        }
        val arena = repository.create(id)
        plugin.logger.info("[WaterBallonZ] Created arena '${arena.id}'.")
        return arena
    }

    fun deleteArena(id: String): Boolean {
        if (!repository.exists(id)) return false
        if (::matchManager.isInitialized) {
            matchManager.arenaDeleted(id)
        }
        return repository.delete(id).also { deleted ->
            if (deleted) plugin.logger.info("[WaterBallonZ] Deleted arena '$id'.")
        }
    }

    fun setEnabled(id: String, enabled: Boolean): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.enabled = enabled
        repository.update(arena)
        if (enabled) {
            plugin.logger.info("[WaterBallonZ] Arena '${arena.id}' enabled.")
        } else {
            plugin.logger.info("[WaterBallonZ] Arena '${arena.id}' disabled.")
            if (::matchManager.isInitialized) matchManager.arenaDisabled(id)
        }
        return arena
    }

    fun setWaitingRoom(id: String, location: Location): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.waitingRoom = StoredLocation.from(location) ?: return null
        repository.update(arena)
        return arena
    }

    fun setSpawn(id: String, team: String, location: Location): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        val stored = StoredLocation.from(location) ?: return null
        when (team.lowercase()) {
            "red" -> arena.redSpawn = stored
            "blue" -> arena.blueSpawn = stored
            else -> throw IllegalArgumentException("Team must be 'red' or 'blue'.")
        }
        repository.update(arena)
        return arena
    }

    fun setSpectator(id: String, location: Location): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.spectator = StoredLocation.from(location) ?: return null
        repository.update(arena)
        return arena
    }

    fun setMinPlayers(id: String, value: Int): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.minimumPlayers = value.coerceAtLeast(2)
        if (arena.minimumPlayers > arena.maximumPlayers) {
            arena.maximumPlayers = arena.minimumPlayers
        }
        repository.update(arena)
        return arena
    }

    fun setMaxPlayers(id: String, value: Int): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.maximumPlayers = value.coerceAtLeast(arena.minimumPlayers)
        repository.update(arena)
        return arena
    }

    fun setCountdownSeconds(id: String, value: Int): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.countdownSeconds = value.coerceIn(1, 60)
        repository.update(arena)
        return arena
    }

    fun setMaximumDuration(id: String, value: Int): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.maximumDurationSeconds = value.coerceAtLeast(30)
        repository.update(arena)
        return arena
    }

    fun setAutoRestart(id: String, value: Boolean): ArenaDefinition? {
        val arena = repository.get(id) ?: return null
        arena.autoRestart = value
        repository.update(arena)
        return arena
    }

    fun validate(id: String, gameConfig: GameConfig): ArenaValidator.ValidationResult? {
        val arena = repository.get(id) ?: return null
        return validator.validate(arena, gameConfig)
    }

    fun canStart(id: String, gameConfig: GameConfig, currentPlayers: Int): ArenaValidator.ValidationResult? {
        val arena = repository.get(id) ?: return null
        return validator.canStart(arena, gameConfig, currentPlayers)
    }

    fun reportValidationDiagnostics() {
        val gameConfig = plugin.registry.gameConfig
        for (arena in repository.all()) {
            val result = validator.validate(arena, gameConfig)
            if (!result.isValid) {
                plugin.logger.warning("[WaterBallonZ] ${result.format()}")
            } else {
                plugin.logger.info("[WaterBallonZ] Arena '${arena.id}' is valid and ready.")
            }
        }
    }
}
