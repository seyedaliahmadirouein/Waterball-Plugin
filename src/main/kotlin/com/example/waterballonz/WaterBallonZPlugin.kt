package com.example.waterballonz

import com.example.waterballonz.arena.ArenaService
import com.example.waterballonz.arena.ArenaValidator
import com.example.waterballonz.arena.repository.ArenaRepository
import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.combat.CombatService
import com.example.waterballonz.combat.ComboTracker
import com.example.waterballonz.commands.WaterBallonZCommand
import com.example.waterballonz.configuration.ConfigManager
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.health.GameHealthService
import com.example.waterballonz.items.WaterBallItem
import com.example.waterballonz.listeners.ArenaListener
import com.example.waterballonz.listeners.CombatListener
import com.example.waterballonz.listeners.ExploitPreventionListener
import com.example.waterballonz.listeners.PlayerLifecycleListener
import com.example.waterballonz.lobby.LobbyService
import com.example.waterballonz.match.MatchManager
import com.example.waterballonz.messages.MessageService
import com.example.waterballonz.persistence.StatisticsService
import com.example.waterballonz.placeholders.PlaceholderHook
import com.example.waterballonz.player.PlayerSessionService
import com.example.waterballonz.player.PlayerSnapshotService
import com.example.waterballonz.scheduler.WbzScheduler
import com.example.waterballonz.team.TeamBalancer
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Root plugin class for WaterBallonZ.
 *
 * This class is responsible for lifecycle orchestration only — actual gameplay
 * behavior is delegated to the services held by [ServiceRegistry].
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
        val comboTracker = ComboTracker(gameConfig.combat.comboWindowMillis)
        val healthService = GameHealthService(gameConfig)
        val waterBallItem = WaterBallItem(this, gameConfig)
        val snapshotService = PlayerSnapshotService(this, gameConfig)
        val sessionService = PlayerSessionService(this)
        val statisticsService = StatisticsService(this, gameConfig).also { it.init() }
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
            comboTracker = comboTracker,
            healthService = healthService,
            waterBallItem = waterBallItem,
            snapshotService = snapshotService,
            sessionService = sessionService,
            statisticsService = statisticsService,
            combatService = combatService,
            matchManager = matchManager,
        )
        arenaService.attachMatchManager(matchManager)

        // Listeners
        val listenerRegistrar: (org.bukkit.event.Listener) -> Unit = { listener ->
            server.pluginManager.registerEvents(listener, this)
        }
        listOf(
            PlayerLifecycleListener(registry),
            ArenaListener(registry),
            CombatListener(registry),
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

        // Periodic match-state HUD refresh task (every 10 ticks / 0.5s by default)
        val refreshTicks = gameConfig.hud.healthRefreshTicks.toLong().coerceAtLeast(1L)
        periodicTasks += scheduler.runRepeating(refreshTicks, refreshTicks) {
            matchManager.refreshHuds()
        }

        // Startup diagnostics
        logger.info("[WaterBallonZ] Enabled. Java: ${System.getProperty("java.version")}, " +
            "Server: ${server.name} ${server.version}")
        logger.info("[WaterBallonZ] Loaded ${arenaService.arenaCount()} arena(s).")
        arenaService.reportValidationDiagnostics()
    }

    override fun onDisable() {
        // Stop all matches safely and release player state.
        if (::registry.isInitialized) {
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
