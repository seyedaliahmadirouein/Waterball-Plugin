package com.example.waterballonz

import com.example.waterballonz.arena.ArenaService
import com.example.waterballonz.arena.ArenaValidator
import com.example.waterballonz.arena.repository.ArenaRepository
import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.combat.CombatService
import com.example.waterballonz.combat.ComboTracker
import com.example.waterballonz.combat.projectile.ArmorStandFollower
import com.example.waterballonz.combat.projectile.ProjectileSpawner
import com.example.waterballonz.combat.projectile.ProjectileTracker
import com.example.waterballonz.combat.service.CollisionResolver
import com.example.waterballonz.combat.service.DamageService
import com.example.waterballonz.combat.service.KnockbackService
import com.example.waterballonz.commands.WaterBallonZCommand
import com.example.waterballonz.configuration.ConfigManager
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.feedback.CombatFeedbackService
import com.example.waterballonz.feedback.SubtitleHandler
import com.example.waterballonz.health.GameHealthService
import com.example.waterballonz.items.WaterBallItem
import com.example.waterballonz.listeners.ArenaListener
import com.example.waterballonz.listeners.ExploitPreventionListener
import com.example.waterballonz.listeners.PlayerLifecycleListener
import com.example.waterballonz.listeners.SnowballCleanupListener
import com.example.waterballonz.listeners.SnowballHitListener
import com.example.waterballonz.listeners.SnowballLaunchListener
import com.example.waterballonz.lobby.LobbyService
import com.example.waterballonz.match.MatchManager
import com.example.waterballonz.messages.MessageService
import com.example.waterballonz.persistence.StatisticsService
import com.example.waterballonz.placeholders.PlaceholderHook
import com.example.waterballonz.player.PlayerSessionService
import com.example.waterballonz.player.PlayerSnapshotService
import com.example.waterballonz.scheduler.WbzScheduler
import com.example.waterballonz.team.TeamBalancer
import com.example.waterballonz.team.TeamService
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Root plugin class for WaterBallonZ.
 *
 * This class is responsible for lifecycle orchestration only — actual gameplay
 * behavior is delegated to the services held by [ServiceRegistry].
 *
 * v2.0.0 architecture refactor (per spec section 21):
 *  - Combat is split into focused services: ProjectileSpawner, ProjectileTracker,
 *    DamageService, KnockbackService, CollisionResolver, SubtitleHandler,
 *    CombatFeedbackService, TeamService.
 *  - Combat listeners are split into SnowballLaunchListener, SnowballHitListener,
 *    SnowballCleanupListener (replacing the old monolithic CombatListener).
 *  - Projectile tracking is centralized in ProjectileTracker, no longer tied
 *    to MatchManager's per-match map.
 */
class WaterBallonZPlugin : JavaPlugin() {

    lateinit var registry: ServiceRegistry
        private set

    private val periodicTasks = mutableListOf<BukkitTask>()

    override fun onEnable() {
        // Initialise configuration
        val configManager = ConfigManager(this)
        configManager.loadAll()
        val gameConfig: GameConfig = configManager.gameConfig

        // Messages
        val messageService = MessageService(this, configManager)
        messageService.reload()

        // Scheduler abstraction (Paper-friendly, swappable for Folia later)
        val scheduler = WbzScheduler(this)

        // Repositories and core services
        val arenaRepository = ArenaRepository(this, configManager)
        arenaRepository.reload()

        val arenaValidator = ArenaValidator(this, arenaRepository)
        val arenaService = ArenaService(this, arenaRepository, arenaValidator)

        val lobbyService = LobbyService(this, configManager)
        lobbyService.load()

        val teamBalancer = TeamBalancer(gameConfig)
        val teamService = TeamService(
            sessionService = PlayerSessionService(this), // placeholder, replaced below
            teamBalancer = teamBalancer,
        )
        val comboTracker = ComboTracker(gameConfig.combat.comboWindowMillis)
        val healthService = GameHealthService(gameConfig)
        val waterBallItem = WaterBallItem(this, gameConfig)
        val snapshotService = PlayerSnapshotService(this, gameConfig)
        val sessionService = PlayerSessionService(this)
        val statisticsService = StatisticsService(this, gameConfig).also { it.init() }

        // The "old" CombatService is now a thin compatibility shim — the
        // real combat pipeline lives in the new ProjectileSpawner +
        // CollisionResolver + DamageService + KnockbackService + feedback
        // chain below.
        val combatService = CombatService(
            plugin = this,
            gameConfig = gameConfig,
            comboTracker = comboTracker,
            healthService = healthService,
            waterBallItem = waterBallItem,
            sessionService = sessionService,
            statisticsService = statisticsService,
            messageService = messageService,
        )

        // Replace teamService's sessionService with the real one we just built.
        val teamServiceReal = TeamService(sessionService, teamBalancer)

        val matchManager = MatchManager(
            plugin = this,
            gameConfig = gameConfig,
            arenaService = arenaService,
            sessionService = sessionService,
            snapshotService = snapshotService,
            teamBalancer = teamBalancer,
            combatService = combatService,
            healthService = healthService,
            lobbyService = lobbyService,
            statisticsService = statisticsService,
            messageService = messageService,
            scheduler = scheduler,
        )

        // v2.0.0 new combat services — wired together with dependency injection.
        val projectileTracker = ProjectileTracker(this)
        val armorStandFollower = ArmorStandFollower(this, gameConfig)
        val projectileSpawner = ProjectileSpawner(
            plugin = this,
            gameConfig = gameConfig,
            waterBallItem = waterBallItem,
            tracker = projectileTracker,
            armorStandFollower = armorStandFollower,
            sessionService = sessionService,
            matchManager = matchManager,
        )
        val damageService = DamageService(
            gameConfig = gameConfig,
            healthService = healthService,
            comboTracker = comboTracker,
            sessionService = sessionService,
        )
        val knockbackService = KnockbackService(gameConfig)
        val subtitleHandler = SubtitleHandler(this, gameConfig)
        val feedbackService = CombatFeedbackService(
            plugin = this,
            gameConfig = gameConfig,
            subtitleHandler = subtitleHandler,
            messageService = messageService,
        )
        val collisionResolver = CollisionResolver(
            plugin = this,
            gameConfig = gameConfig,
            tracker = projectileTracker,
            damageService = damageService,
            knockbackService = knockbackService,
            feedbackService = feedbackService,
            matchManager = matchManager,
            statisticsService = statisticsService,
        )

        // Wire services into registry
        registry = ServiceRegistry(
            plugin = this,
            configManager = configManager,
            messageService = messageService,
            scheduler = scheduler,
            arenaService = arenaService,
            arenaValidator = arenaValidator,
            arenaRepository = arenaRepository,
            lobbyService = lobbyService,
            teamBalancer = teamBalancer,
            teamService = teamServiceReal,
            comboTracker = comboTracker,
            healthService = healthService,
            waterBallItem = waterBallItem,
            snapshotService = snapshotService,
            sessionService = sessionService,
            statisticsService = statisticsService,
            combatService = combatService,
            matchManager = matchManager,
            // v2.0.0 new services
            projectileTracker = projectileTracker,
            armorStandFollower = armorStandFollower,
            projectileSpawner = projectileSpawner,
            damageService = damageService,
            knockbackService = knockbackService,
            collisionResolver = collisionResolver,
            subtitleHandler = subtitleHandler,
            feedbackService = feedbackService,
        )
        arenaService.attachMatchManager(matchManager)

        // Listeners — split per spec section 12.
        val listenerRegistrar: (org.bukkit.event.Listener) -> Unit = { listener ->
            server.pluginManager.registerEvents(listener, this)
        }
        listOf(
            PlayerLifecycleListener(registry),
            ArenaListener(registry),
            SnowballLaunchListener(registry),
            SnowballHitListener(registry),
            SnowballCleanupListener(registry),
            ExploitPreventionListener(registry),
        ).forEach(listenerRegistrar)

        // Commands
        WaterBallonZCommand(registry).register()

        // PlaceholderAPI hook (optional)
        if (gameConfig.integrations.placeholderapiEnabled && server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            PlaceholderHook(registry).register()
            logger.info("[WaterBallonZ] PlaceholderAPI hook enabled.")
        } else {
            logger.info("[WaterBallonZ] PlaceholderAPI not present — placeholders disabled.")
        }

        // Start the centralized projectile-tracker maintenance task. ONE task
        // for the whole plugin — not one per projectile.
        projectileTracker.startMaintenanceTask()

        // Periodic match-state HUD refresh task (every 10 ticks / 0.5s by default)
        val refreshTicks = gameConfig.hud.healthRefreshTicks.toLong().coerceAtLeast(1L)
        periodicTasks += scheduler.runRepeating(refreshTicks, refreshTicks) {
            matchManager.refreshHuds()
        }

        // Startup diagnostics
        logger.info("[WaterBallonZ] v2.0.0 enabled. Java: ${System.getProperty("java.version")}, " +
            "Server: ${server.name} ${server.version}")
        logger.info("[WaterBallonZ] Loaded ${arenaService.arenaCount()} arena(s).")
        logger.info("[WaterBallonZ] ArmorStand follower: ${if (gameConfig.snowball.tracker.armorStandEnabled) "ENABLED" else "DISABLED"}")
        logger.info("[WaterBallonZ] Debug logging: ${if (gameConfig.general.debug) "ON" else "OFF"}")
        arenaService.reportValidationDiagnostics()
    }

    override fun onDisable() {
        // Stop all matches safely and release player state.
        if (::registry.isInitialized) {
            registry.projectileTracker.shutdown()
            registry.matchManager.shutdownAllMatches()
            registry.statisticsService.close()
        }
        periodicTasks.forEach { it.cancel() }
        periodicTasks.clear()
        logger.info("[WaterBallonZ] Disabled.")
    }

    /** Convenience accessor used by services that need the plugin instance. */
    fun wbz(): WaterBallonZPlugin = this
}
