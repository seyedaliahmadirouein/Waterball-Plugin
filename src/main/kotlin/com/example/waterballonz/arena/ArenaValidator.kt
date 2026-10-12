package com.example.waterballonz.arena

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.repository.ArenaRepository
import com.example.waterballonz.configuration.GameConfig

/**
 * Verifies that arenas are ready to host a match.
 *
 * Returns actionable error messages that the admin command can surface to
 * the user. Never throws — invalid arenas are reported, not crashed.
 */
class ArenaValidator(
    private val plugin: WaterBallonZPlugin,
    private val repository: ArenaRepository,
) {

    fun validate(arena: ArenaDefinition, gameConfig: GameConfig): ValidationResult {
        val errors = arena.validate(gameConfig)
        return ValidationResult(arena.id, errors)
    }

    /** Quick check whether the arena can be started *right now*. */
    fun canStart(arena: ArenaDefinition, gameConfig: GameConfig, currentPlayers: Int): ValidationResult {
        val errors = arena.validate(gameConfig).toMutableList()
        if (currentPlayers < arena.minimumPlayers) {
            errors += "Player count ($currentPlayers) is below minimum (${arena.minimumPlayers})."
        }
        if (currentPlayers > arena.maximumPlayers) {
            errors += "Player count ($currentPlayers) exceeds maximum (${arena.maximumPlayers})."
        }
        return ValidationResult(arena.id, errors)
    }

    fun allArenasValidation(gameConfig: GameConfig): List<ValidationResult> =
        repository.all().map { validate(it, gameConfig) }

    data class ValidationResult(val arenaId: String, val errors: List<String>) {
        val isValid: Boolean get() = errors.isEmpty()
        fun format(): String =
            if (isValid) "Arena '$arenaId' is valid."
            else "Arena '$arenaId' has ${errors.size} problem(s):\n" + errors.joinToString("\n") { " - $it" }
    }
}
