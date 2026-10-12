package com.example.waterballonz.match

import com.example.waterballonz.arena.ArenaDefinition
import com.example.waterballonz.combat.ComboTracker
import com.example.waterballonz.combat.CombatService
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.health.GameHealthService
import com.example.waterballonz.lobby.LobbyService
import com.example.waterballonz.messages.MessageService
import com.example.waterballonz.persistence.StatisticsService
import com.example.waterballonz.player.Participant
import com.example.waterballonz.player.PlayerSession
import com.example.waterballonz.player.PlayerSessionService
import com.example.waterballonz.player.PlayerSnapshotService
import com.example.waterballonz.scheduler.WbzScheduler
import com.example.waterballonz.team.Team
import com.example.waterballonz.team.TeamBalancer
import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.ArenaState
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * One running WaterBallonZ match.
 *
 * Holds the immutable [definition] (snapshot taken at match start), the
 * [matchId], the current [state] of the state machine, the [startMillis] for
 * match-timer calculations, and references to the cancelable tasks owned by
 * this match.
 *
 * Mutable state lives here, but **mutations** are routed through the
 * [MatchManager] so that lifecycle invariants (state transitions, task
 * cancellation, double-end prevention) are enforced in one place.
 */
class Match(
    val matchId: Long,
    val definition: ArenaDefinition,
    val gameConfig: GameConfig,
    val startMillis: Long,
) {
    @Volatile
    var state: ArenaState = ArenaState.WAITING
        internal set

    @Volatile
    var startCountdown: Int = 0
        internal set

    @Volatile
    var matchSecondsElapsed: Int = 0
        internal set

    @Volatile
    var endProcessed: Boolean = false
        internal set

    @Volatile
    var winner: Team? = null
        internal set

    @Volatile
    var isDraw: Boolean = false
        internal set

    internal val tasks: MutableSet<BukkitTask> = java.util.Collections.synchronizedSet(HashSet())

    internal val projectiles: MutableMap<UUID, ProjectileInfo> = java.util.Collections.synchronizedMap(HashMap())

    fun isActive(): Boolean = state == ArenaState.IN_GAME || state == ArenaState.COUNTDOWN

    fun isWaiting(): Boolean = state == ArenaState.WAITING || state == ArenaState.STARTING

    fun arenaId(): String = definition.id
}

/** Lightweight metadata tracked for every launched WaterBall projectile. */
data class ProjectileInfo(
    val projectileId: UUID,
    val shooterId: UUID,
    val arenaId: String,
    val matchId: Long,
    val launchTimeMillis: Long,
    var resolved: Boolean = false,
)

/**
 * Manages the lifecycle of every [Match].
 *
 * Each arena can host at most one match at a time. The [MatchManager] holds
 * the authoritative map of arena-id → match.
 *
 * State-machine invariants enforced here:
 *   1. An arena starts at most one match at a time.
 *   2. The match never starts with an empty team.
 *   3. Required spawn locations are validated before COUNTDOWN.
 *   4. Countdown cancels if the player count falls below the minimum.
 *   5. End-game rewards and statistics are processed at most once.
 *   6. All match tasks are cancelled on end.
 *   7. Stale projectiles are invalidated on end.
 *   8. Players are routed to the configured return location on end.
 */
class MatchManager(
    private val plugin: WaterBallonZPlugin,
    private val gameConfig: GameConfig,
    private val arenaService: com.example.waterballonz.arena.ArenaService,
    private val sessionService: PlayerSessionService,
    private val snapshotService: PlayerSnapshotService,
    private val teamBalancer: TeamBalancer,
    private val combatService: CombatService,
    private val healthService: GameHealthService,
    private val lobbyService: LobbyService,
    private val statisticsService: StatisticsService,
    private val messageService: MessageService,
    private val scheduler: WbzScheduler,
) {
    private val matchesByArena: MutableMap<String, Match> = java.util.Collections.synchronizedMap(HashMap())
    private val matchIdGenerator = AtomicLong(0)

    fun matchOf(arenaId: String): Match? = matchesByArena[arenaId]

    fun activeMatchOf(player: Player): Match? {
        val session = sessionService.sessionOf(player.uniqueId) ?: return null
        return matchesByArena[session.arenaId]
    }

    fun refreshHuds() {
        // Triggered periodically — refreshes action bars and scoreboards
        // without producing duplicate combat updates.
        for (match in matchesByArena.values.toList()) {
            if (match.state != ArenaState.IN_GAME) continue
            for (session in sessionService.sessionsIn(match.arenaId())) {
                if (session.participant.alive && !session.isSpectator) {
                    messageService.sendActionBar(
                        session.playerId.toPlayerSafe(),
                        "hud.actionbar.hp",
                        "hp" to session.participant.hp,
                        "ammo" to session.participant.ammo,
                        "combo" to session.participant.comboHits,
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------------------------
    // Lifecycle entry points
    // --------------------------------------------------------------------------------

    /**
     * Add a player to [arenaId]'s waiting room.
     */
    fun join(player: Player, arenaId: String): Result<Unit> {
        if (sessionService.isParticipant(player.uniqueId)) {
            return Result.failure(IllegalStateException("You are already in a WaterBallonZ session. Use /wbz leave first."))
        }
        val arena = arenaService.getArena(arenaId)
            ?: return Result.failure(IllegalArgumentException("Arena '$arenaId' not found."))

        if (!arena.enabled) {
            return Result.failure(IllegalStateException("Arena '${arena.id}' is disabled."))
        }

        val match = matchesByArena[arena.id] ?: createMatch(arena)
        if (match.state != ArenaState.WAITING && match.state != ArenaState.STARTING) {
            return Result.failure(IllegalStateException("Arena '${arena.id}' is currently ${match.state}."))
        }

        val currentCount = sessionService.countIn(arena.id)
        if (currentCount >= arena.maximumPlayers) {
            return Result.failure(IllegalStateException("Arena '${arena.id}' is full."))
        }

        // Snapshot player state and teleport to waiting room
        snapshotService.capture(player)
        val waiting = arena.waitingRoom?.resolve()
            ?: return Result.failure(IllegalStateException("Arena '${arena.id}' has no waiting room set."))
        player.teleport(waiting)

        val team = teamBalancer.assign(player.uniqueId, maxPerTeam = (arena.maximumPlayers + 1) / 2)
            ?: return Result.failure(IllegalStateException("Could not assign team — both teams are full."))

        val matchForSession = match // ensure stable reference
        val session = sessionService.startSession(
            player = player,
            arena = arena,
            match = matchForSession,
            team = team,
            hp = healthService.startingHpForNewMatch(),
            ammo = gameConfig.waterball.startingAmount,
        )

        messageService.send(player, "join.assigned",
            "arena" to arena.id, "team" to team.id, "color" to team.miniMessageColor, "team_name" to team.displayName)

        broadcastMatch(match, "join.broadcast",
            "player" to player.name, "current" to currentCount + 1, "max" to arena.maximumPlayers)

        // Auto-start if minimum satisfied
        if (currentCount + 1 >= arena.minimumPlayers && gameConfig.general.autoStart) {
            tryStartMatch(arena.id)
        }
        return Result.success(Unit)
    }

    fun leave(player: Player): Result<Unit> {
        val session = sessionService.sessionOf(player.uniqueId)
            ?: return Result.failure(IllegalStateException("You are not in a WaterBallonZ session."))

        val match = matchesByArena[session.arenaId]
        handlePlayerLeave(player, match, session, eliminateIfActive = false)
        return Result.success(Unit)
    }

    fun forceStart(arenaId: String): Result<Unit> {
        val arena = arenaService.getArena(arenaId)
            ?: return Result.failure(IllegalArgumentException("Arena '$arenaId' not found."))
        return tryStartMatch(arena.id)
    }

    fun forceStop(arenaId: String): Result<Unit> {
        val match = matchesByArena[arenaId]
            ?: return Result.failure(IllegalStateException("Arena '$arenaId' has no active match."))
        endMatch(match, winner = null, isDraw = false, isStop = true)
        return Result.success(Unit)
    }

    fun shutdownAllMatches() {
        for (match in matchesByArena.values.toList()) {
            endMatch(match, winner = null, isDraw = false, isStop = true)
        }
        matchesByArena.clear()
        teamBalancer.clear()
        sessionService.allSessions().forEach { session ->
            val player = Bukkit.getPlayer(session.playerId)
            if (player != null) {
                snapshotService.restore(player)
                lobbyService.sendToLobby(player, session.preGameReturnLocation)
            }
            snapshotService.dropSnapshot(session.playerId)
        }
        // PlayerSessionService has no clearAll() — we already ended sessions individually
    }

    fun arenaDisabled(arenaId: String) {
        val match = matchesByArena[arenaId] ?: return
        endMatch(match, winner = null, isDraw = false, isStop = true)
    }

    fun arenaDeleted(arenaId: String) {
        arenaDisabled(arenaId)
        matchesByArena.remove(arenaId)
    }

    // --------------------------------------------------------------------------------
    // Internal lifecycle helpers
    // --------------------------------------------------------------------------------

    private fun createMatch(arena: ArenaDefinition): Match {
        val match = Match(
            matchId = matchIdGenerator.incrementAndGet(),
            definition = arena, // snapshot reference; admin reloads don't mutate it
            gameConfig = gameConfig,
            startMillis = System.currentTimeMillis(),
        )
        matchesByArena[arena.id] = match
        return match
    }

    private fun tryStartMatch(arenaId: String): Result<Unit> {
        val match = matchesByArena[arenaId] ?: return Result.failure(IllegalStateException("No match in arena '$arenaId'."))
        if (!match.state.canTransitionTo(ArenaState.STARTING)) {
            return Result.failure(IllegalStateException("Arena '$arenaId' is in state ${match.state}; cannot start."))
        }
        val arena = match.definition
        val validation = arenaService.canStart(arena.id, gameConfig, sessionService.countIn(arena.id))
            ?: return Result.failure(IllegalStateException("Arena '$arenaId' not found."))
        if (!validation.isValid) {
            return Result.failure(IllegalStateException(validation.format()))
        }
        match.state = ArenaState.STARTING
        startCountdown(match)
        return Result.success(Unit)
    }

    private fun startCountdown(match: Match) {
        match.state = ArenaState.COUNTDOWN
        val arena = match.definition

        // Teleport to team spawns & freeze
        for (session in sessionService.sessionsIn(match.arenaId())) {
            val player = Bukkit.getPlayer(session.playerId) ?: continue
            val spawn = when (session.participant.team) {
                Team.RED -> arena.redSpawn?.resolve()
                Team.BLUE -> arena.blueSpawn?.resolve()
            }
            if (spawn != null) player.teleport(spawn)
            session.movementLocked = true
            // Give starting ammo now (so they have it during countdown)
            giveWaterBalls(player, session, match, gameConfig.waterball.startingAmount)
        }

        val totalSeconds = arena.countdownSeconds
        var remaining = totalSeconds
        val countdownTask = scheduler.runRepeating(0L, 20L) {
            if (match.state != ArenaState.COUNTDOWN) {
                return@runRepeating
            }
            if (sessionService.countIn(match.arenaId()) < arena.minimumPlayers) {
                cancelCountdown(match)
                broadcastMatch(match, "countdown.cancelled")
                return@runRepeating
            }
            if (remaining <= 0) {
                startActive(match)
                return@runRepeating
            }
            val messageKey = if (remaining == 1) "countdown.1" else if (remaining == 2) "countdown.2" else if (remaining == 3) "countdown.3" else "countdown.number"
            for (session in sessionService.sessionsIn(match.arenaId())) {
                val player = Bukkit.getPlayer(session.playerId) ?: continue
                messageService.sendTitle(
                    player,
                    "countdown.title",
                    messageKey,
                    fadeIn = 0, stay = 20, fadeOut = 0,
                    "n" to remaining,
                )
                // play sound
                if (gameConfig.effects.countdownSoundEnabled) {
                    player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.5f)
                }
            }
            remaining--
        }
        match.tasks += countdownTask
    }

    private fun cancelCountdown(match: Match) {
        match.tasks.forEach { it.cancel() }
        match.tasks.clear()
        for (session in sessionService.sessionsIn(match.arenaId())) {
            session.movementLocked = false
        }
        match.state = ArenaState.WAITING
    }

    private fun startActive(match: Match) {
        match.tasks.forEach { it.cancel() }
        match.tasks.clear()

        match.state = ArenaState.IN_GAME
        broadcastMatch(match, "match.start")
        for (session in sessionService.sessionsIn(match.arenaId())) {
            val player = Bukkit.getPlayer(session.playerId) ?: continue
            session.movementLocked = false
            messageService.sendTitle(
                player,
                "countdown.title",
                "countdown.go",
                fadeIn = 0, stay = 20, fadeOut = 0,
            )
            if (gameConfig.effects.startSoundEnabled) {
                player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 1.5f)
            }
        }

        // Schedule match-timer / regen / refill tasks
        scheduleMatchTimer(match)
        scheduleRegeneration(match)
        scheduleRefill(match)
    }

    private fun scheduleMatchTimer(match: Match) {
        val maxSeconds = match.definition.maximumDurationSeconds
        val task = scheduler.runRepeating(20L, 20L) {
            if (match.state != ArenaState.IN_GAME) return@runRepeating
            match.matchSecondsElapsed++
            if (match.matchSecondsElapsed >= maxSeconds) {
                applyTimeoutResult(match)
            }
        }
        match.tasks += task
    }

    private fun scheduleRegeneration(match: Match) {
        if (!gameConfig.health.regenerationEnabled) return
        val intervalTicks = gameConfig.health.regenerationIntervalSeconds.toLong() * 20L
        val task = scheduler.runRepeating(intervalTicks, intervalTicks) {
            if (match.state != ArenaState.IN_GAME) return@runRepeating
            for (session in sessionService.sessionsIn(match.arenaId())) {
                if (!session.participant.alive) continue
                val newHp = healthService.regenerate(session.participant.hp)
                session.participant.hp = newHp
                val player = Bukkit.getPlayer(session.playerId) ?: continue
                messageService.sendActionBar(player, "hud.actionbar.regen", "hp" to newHp)
            }
        }
        match.tasks += task
    }

    private fun scheduleRefill(match: Match) {
        if (gameConfig.waterball.additionalPerMinute <= 0) return
        val intervalTicks = 20L * 60L // every minute
        val task = scheduler.runRepeating(intervalTicks, intervalTicks) {
            if (match.state != ArenaState.IN_GAME) return@runRepeating
            for (session in sessionService.sessionsIn(match.arenaId())) {
                if (!session.participant.alive) continue
                val player = Bukkit.getPlayer(session.playerId) ?: continue
                session.participant.ammo = (session.participant.ammo + gameConfig.waterball.additionalPerMinute)
                    .coerceAtMost(gameConfig.waterball.maximumHeld)
                giveWaterBalls(player, session, match, gameConfig.waterball.additionalPerMinute)
                messageService.sendActionBar(player, "hud.actionbar.refill", "ammo" to session.participant.ammo)
            }
        }
        match.tasks += task
    }

    // --------------------------------------------------------------------------------
    // End / cleanup / victory
    // --------------------------------------------------------------------------------

    fun eliminate(player: Player, match: Match) {
        val session = sessionService.sessionOf(player.uniqueId) ?: return
        if (session.participant.eliminated) return

        session.participant.alive = false
        session.participant.eliminated = true
        session.participant.hp = 0
        combatService.comboTracker.reset(player.uniqueId)

        // Move to spectator or lobby
        if (match.definition.spectator != null) {
            val spec = match.definition.spectator?.resolve()
            if (spec != null) {
                player.teleport(spec)
                session.isSpectator = true
            }
        } else {
            // Route to lobby
            snapshotService.restore(player)
            lobbyService.sendToLobby(player, session.preGameReturnLocation)
        }

        broadcastMatch(match, "elimination.broadcast",
            "player" to player.name, "team" to session.participant.team.id)

        checkWinCondition(match)
    }

    private fun checkWinCondition(match: Match) {
        if (match.endProcessed) return
        val redAlive = sessionService.aliveParticipantsIn(match.arenaId()).count { it.team == Team.RED }
        val blueAlive = sessionService.aliveParticipantsIn(match.arenaId()).count { it.team == Team.BLUE }

        if (redAlive == 0 && blueAlive == 0) {
            endMatch(match, winner = null, isDraw = true)
        } else if (redAlive == 0) {
            endMatch(match, winner = Team.BLUE, isDraw = false)
        } else if (blueAlive == 0) {
            endMatch(match, winner = Team.RED, isDraw = false)
        }
    }

    private fun applyTimeoutResult(match: Match) {
        if (match.endProcessed) return
        val redAlive = sessionService.aliveParticipantsIn(match.arenaId()).count { it.team == Team.RED }
        val blueAlive = sessionService.aliveParticipantsIn(match.arenaId()).count { it.team == Team.BLUE }
        if (redAlive == blueAlive) {
            endMatch(match, winner = null, isDraw = true)
        } else {
            endMatch(match, winner = if (redAlive > blueAlive) Team.RED else Team.BLUE, isDraw = false)
        }
    }

    fun endMatch(match: Match, winner: Team?, isDraw: Boolean, isStop: Boolean = false) {
        if (match.endProcessed) return
        match.endProcessed = true
        match.state = ArenaState.ENDING
        match.winner = winner
        match.isDraw = isDraw

        // Cancel all tasks
        match.tasks.forEach { it.cancel() }
        match.tasks.clear()

        // Invalidate outstanding projectiles
        for ((_, info) in match.projectiles.toMap()) {
            info.resolved = true
        }
        match.projectiles.clear()

        // Record match-result statistics for every participant (once)
        if (gameConfig.statistics.enabled) {
            for (session in sessionService.sessionsIn(match.arenaId())) {
                val won = !isDraw && session.participant.team == winner
                val drawn = isDraw
                statisticsService.recordMatchResultAsync(
                    session.playerId, won, drawn, session.participant.team
                )
                statisticsService.updateHighestComboAsync(session.playerId, session.participant.highestCombo)
            }
        }

        // Broadcast winner
        val messageKey = if (isDraw) "end.draw" else if (winner == null) "end.stopped" else "end.victory"
        broadcastMatch(match, messageKey,
            "winner" to (winner?.displayName ?: "—"),
            "color" to (winner?.miniMessageColor ?: "<gray>"))

        // Schedule end-delay then reset
        scheduler.runDelayed(gameConfig.game.endDelaySeconds.toLong() * 20L) {
            resetMatch(match)
        }
    }

    private fun resetMatch(match: Match) {
        match.state = ArenaState.RESETTING
        // Restore and teleport players
        for (session in sessionService.sessionsIn(match.arenaId()).toList()) {
            val player = Bukkit.getPlayer(session.playerId)
            if (player != null) {
                snapshotService.restore(player)
                if (gameConfig.general.returnToLobbyAfterMatch) {
                    lobbyService.sendToLobby(player, session.preGameReturnLocation)
                } else {
                    snapshotService.restoreOriginalLocation(player)
                }
            }
            teamBalancer.remove(session.playerId)
            sessionService.endSession(session.playerId)
            snapshotService.dropSnapshot(session.playerId)
        }

        // Cleanup combo tracker entries for the arena's participants
        val stillOnline = Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
        combatService.comboTracker.cleanupDisconnectedPlayers(stillOnline)

        if (match.definition.autoRestart) {
            match.state = ArenaState.WAITING
            match.endProcessed = false
            match.matchSecondsElapsed = 0
            match.winner = null
            match.isDraw = false
        } else {
            matchesByArena.remove(match.arenaId())
        }
    }

    // --------------------------------------------------------------------------------
    // Player lifecycle helpers
    // --------------------------------------------------------------------------------

    fun handlePlayerLeave(player: Player, match: Match?, session: PlayerSession, eliminateIfActive: Boolean) {
        match ?: run {
            // No match — just cleanup session & snapshot
            teamBalancer.remove(player.uniqueId)
            sessionService.endSession(session.playerId)
            snapshotService.restore(player)
            lobbyService.sendToLobby(player, session.preGameReturnLocation)
            snapshotService.dropSnapshot(session.playerId)
            return
        }
        if (match.state == ArenaState.IN_GAME && eliminateIfActive && gameConfig.disconnect.eliminateOnDisconnect) {
            // Treat as elimination — check victory
            eliminate(player, match)
        } else if (match.state == ArenaState.COUNTDOWN || match.state == ArenaState.STARTING) {
            // Mid-countdown leave: just remove the player
            cancelCountdownIfBelowMinimum(match)
        }

        teamBalancer.remove(player.uniqueId)
        sessionService.endSession(session.playerId)
        snapshotService.restore(player)
        lobbyService.sendToLobby(player, session.preGameReturnLocation)
        snapshotService.dropSnapshot(session.playerId)
    }

    private fun cancelCountdownIfBelowMinimum(match: Match) {
        if (match.state != ArenaState.COUNTDOWN && match.state != ArenaState.STARTING) return
        val count = sessionService.countIn(match.arenaId())
        if (count < match.definition.minimumPlayers) {
            cancelCountdown(match)
            broadcastMatch(match, "countdown.cancelled")
        }
    }

    // --------------------------------------------------------------------------------
    // WaterBall item delivery
    // --------------------------------------------------------------------------------

    private fun giveWaterBalls(player: Player, session: PlayerSession, match: Match, amount: Int) {
        if (amount <= 0) return
        val item = combatService.waterBallItem.create(
            amount = amount.coerceAtMost(gameConfig.waterball.maximumHeld),
            arenaId = match.arenaId(),
            matchId = match.matchId,
        )
        val leftover = player.inventory.addItem(item)
        if (leftover.isNotEmpty()) {
            // Drop overflow at player's feet — never silently disappear
            for ((_, stack) in leftover) {
                player.world.dropItemNaturally(player.location, stack)
            }
        }
        session.participant.ammo = (session.participant.ammo + amount).coerceAtMost(gameConfig.waterball.maximumHeld)
    }

    fun registerProjectile(projectileId: UUID, shooter: Player, match: Match) {
        match.projectiles[projectileId] = ProjectileInfo(
            projectileId = projectileId,
            shooterId = shooter.uniqueId,
            arenaId = match.arenaId(),
            matchId = match.matchId,
            launchTimeMillis = System.currentTimeMillis(),
        )
    }

    fun resolveProjectile(projectileId: UUID, match: Match): ProjectileInfo? {
        val info = match.projectiles[projectileId] ?: return null
        if (info.resolved) return null
        info.resolved = true
        return info
    }

    fun projectileInfoOf(projectileId: UUID, match: Match): ProjectileInfo? =
        match.projectiles[projectileId]

    // --------------------------------------------------------------------------------
    // Utilities
    // --------------------------------------------------------------------------------

    fun broadcastMatch(match: Match, key: String, vararg placeholders: Pair<String, Any?>) {
        for (session in sessionService.sessionsIn(match.arenaId())) {
            val player = Bukkit.getPlayer(session.playerId) ?: continue
            messageService.send(player, key, *placeholders)
        }
    }

    private fun UUID.toPlayerSafe(): Player = Bukkit.getPlayer(this) ?: throw IllegalStateException("Player not online")
}
