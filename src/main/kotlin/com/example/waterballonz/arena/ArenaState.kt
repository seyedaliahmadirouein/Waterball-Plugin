package com.example.waterballonz.arena

/**
 * Strongly-typed arena lifecycle states.
 *
 * Allowed transitions:
 *
 *   DISABLED -> WAITING
 *   WAITING  -> DISABLED
 *   WAITING  -> STARTING
 *   STARTING -> COUNTDOWN   (valid configuration + minimum players satisfied)
 *   STARTING -> WAITING      (invalid start conditions or player count lost)
 *   COUNTDOWN -> IN_GAME
 *   COUNTDOWN -> WAITING     (minimum player count lost)
 *   IN_GAME   -> ENDING
 *   ENDING    -> RESETTING
 *   RESETTING -> WAITING
 *   RESETTING -> DISABLED
 *   (Any state -> DISABLED on admin command)
 *
 * All transitions are guarded by [ArenaState.canTransitionTo].
 */
enum class ArenaState {
    DISABLED,
    WAITING,
    STARTING,
    COUNTDOWN,
    IN_GAME,
    ENDING,
    RESETTING;

    fun canTransitionTo(next: ArenaState): Boolean = next in allowedTransitions()

    private fun allowedTransitions(): Set<ArenaState> = when (this) {
        DISABLED -> setOf(WAITING)
        WAITING -> setOf(DISABLED, STARTING)
        STARTING -> setOf(COUNTDOWN, WAITING, DISABLED)
        COUNTDOWN -> setOf(IN_GAME, WAITING, DISABLED)
        IN_GAME -> setOf(ENDING, DISABLED)
        ENDING -> setOf(RESETTING)
        RESETTING -> setOf(WAITING, DISABLED)
    }
}
