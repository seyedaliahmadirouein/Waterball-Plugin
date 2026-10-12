package com.example.waterballonz.bootstrap

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.ArenaService
import com.example.waterballonz.arena.ArenaValidator
import com.example.waterballonz.arena.repository.ArenaRepository
import com.example.waterballonz.combat.CombatService
import com.example.waterballonz.combat.ComboTracker
import com.example.waterballonz.configuration.ConfigManager
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.health.GameHealthService
import com.example.waterballonz.items.WaterBallItem
import com.example.waterballonz.lobby.LobbyService
import com.example.waterballonz.match.MatchManager
import com.example.waterballonz.messages.MessageService
import com.example.waterballonz.persistence.StatisticsService
import com.example.waterballonz.player.PlayerSessionService
import com.example.waterballonz.player.PlayerSnapshotService
import com.example.waterballonz.scheduler.WbzScheduler
import com.example.waterballonz.team.TeamBalancer

/**
 * Holds every long-lived service used by the plugin.
 *
 * The registry is a single "service locator" that listeners, commands,
 * placeholders, and listeners can use without each class having to take ten
 * constructor parameters.
 */
class ServiceRegistry(
    val plugin: WaterBallonZPlugin,
    val configManager: ConfigManager,
    val messageService: MessageService,
    val scheduler: WbzScheduler,
    val arenaService: ArenaService,
    val arenaValidator: ArenaValidator,
    val arenaRepository: ArenaRepository,
    val lobbyService: LobbyService,
    val teamBalancer: TeamBalancer,
    val comboTracker: ComboTracker,
    val healthService: GameHealthService,
    val waterBallItem: WaterBallItem,
    val snapshotService: PlayerSnapshotService,
    val sessionService: PlayerSessionService,
    val statisticsService: StatisticsService,
    val combatService: CombatService,
    val matchManager: MatchManager,
) {
    val gameConfig: GameConfig get() = configManager.gameConfig
}
