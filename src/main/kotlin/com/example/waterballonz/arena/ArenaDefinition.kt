package com.example.waterballonz.arena

import com.example.waterballonz.configuration.GameConfig

/**
 * Persistent configuration for a single arena.
 *
 * Loaded from `arenas.yml` at startup, modified through admin commands, and
 * re-saved immediately on every change.
 *
 * The fields here describe *configuration* — runtime state (current players,
 * match handle, etc.) is held by [ArenaRuntime] / [com.example.waterballonz.match.Match].
 */
data class ArenaDefinition(
    val id: String,
    var enabled: Boolean = false,
    var minimumPlayers: Int = 2,
    var maximumPlayers: Int = 16,
    var countdownSeconds: Int = 3,
    var maximumDurationSeconds: Int = 600,
    var waitingRoom: StoredLocation? = null,
    var redSpawn: StoredLocation? = null,
    var blueSpawn: StoredLocation? = null,
    var spectator: StoredLocation? = null,
    var boundary: ArenaBoundary? = null,
    var teamBalanceStrategy: String = "auto",
    var victoryMessage: String? = null,
    var drawMessage: String? = null,
    var autoRestart: Boolean = false,
) {
    /**
     * Validate against [gameConfig]. Returns a list of error messages — an
     * empty list means the arena is ready to host a match.
     */
    fun validate(gameConfig: GameConfig): List<String> {
        val errors = mutableListOf<String>()

        if (id.isBlank()) errors.add("Arena id is blank.")
        if (minimumPlayers < gameConfig.game.minimumPlayers)
            errors.add("minimum-players ($minimumPlayers) is below server minimum (${gameConfig.game.minimumPlayers}).")
        if (maximumPlayers < minimumPlayers)
            errors.add("maximum-players ($maximumPlayers) is below minimum-players ($minimumPlayers).")
        if (maximumPlayers > gameConfig.game.maximumPlayers)
            errors.add("maximum-players ($maximumPlayers) exceeds server maximum (${gameConfig.game.maximumPlayers}).")
        if (countdownSeconds < 1) errors.add("countdown-seconds must be at least 1.")
        if (maximumDurationSeconds < 30)
            errors.add("maximum-duration-seconds must be at least 30.")

        waitingRoom ?: errors.add("waiting-room location is not set.")
        redSpawn ?: errors.add("red-spawn location is not set.")
        blueSpawn ?: errors.add("blue-spawn location is not set.")

        waitingRoom?.let { if (!it.isWorldAvailable()) errors.add("waiting-room world '${it.worldName}' is not loaded.") }
        redSpawn?.let { if (!it.isWorldAvailable()) errors.add("red-spawn world '${it.worldName}' is not loaded.") }
        blueSpawn?.let { if (!it.isWorldAvailable()) errors.add("blue-spawn world '${it.worldName}' is not loaded.") }
        spectator?.let { if (!it.isWorldAvailable()) errors.add("spectator world '${it.worldName}' is not loaded.") }

        if (gameConfig.game.requireSpectatorLocation && spectator == null)
            errors.add("spectator location is required but not set.")

        boundary?.let { errors.addAll(it.validate()) }

        return errors
    }

    fun isReadyForMatch(gameConfig: GameConfig): Boolean = validate(gameConfig).isEmpty()
}
