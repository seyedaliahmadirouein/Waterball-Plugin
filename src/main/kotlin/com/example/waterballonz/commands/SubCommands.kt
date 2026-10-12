package com.example.waterballonz.commands

import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.team.Team
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

class CreateCommand : AdminSubCommand("create") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val arenaId = args[0]
        try {
            registry.arenaService.createArena(arenaId)
            registry.messageService.send(sender, "create.success", "arena" to arenaId)
        } catch (e: IllegalArgumentException) {
            registry.messageService.send(sender, "create.failure", "arena" to arenaId, "msg" to e.message)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> = emptyList()
}

class DeleteCommand : AdminSubCommand("delete") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val arenaId = args[0]
        val confirmed = args.size >= 2 && args[1].equals("confirm", ignoreCase = true)
        if (!confirmed) {
            registry.messageService.send(sender, "delete.confirm_required", "arena" to arenaId)
            return true
        }
        if (registry.arenaService.deleteArena(arenaId)) {
            registry.messageService.send(sender, "delete.success", "arena" to arenaId)
        } else {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to arenaId)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) return registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) }
        if (args.size == 2 && "confirm".startsWith(args[1])) return listOf("confirm")
        return emptyList()
    }
}

class ListCommand : PlayerSubCommand("list") {
    override val playerOnly = false
    override fun hasPermission(sender: CommandSender): Boolean = true
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        val arenas = registry.arenaService.allArenas()
        if (arenas.isEmpty()) {
            registry.messageService.send(sender, "list.empty")
            return true
        }
        registry.messageService.send(sender, "list.header", "count" to arenas.size)
        for (arena in arenas) {
            val match = registry.matchManager.matchOf(arena.id)
            val state = match?.state?.name?.lowercase() ?: if (arena.enabled) "waiting" else "disabled"
            val players = registry.sessionService.countIn(arena.id)
            registry.messageService.send(sender, "list.line",
                "arena" to arena.id,
                "enabled" to if (arena.enabled) "yes" else "no",
                "players" to players,
                "max" to arena.maximumPlayers,
                "state" to state)
        }
        return true
    }
}

class InfoCommand : AdminSubCommand("info") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val arena = registry.arenaService.getArena(args[0])
            ?: run { registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0]); return true }
        val result = registry.arenaService.validate(arena.id, registry.gameConfig)!!
        registry.messageService.send(sender, "info.header", "arena" to arena.id)
        registry.messageService.send(sender, "info.line", "key" to "enabled", "value" to arena.enabled)
        registry.messageService.send(sender, "info.line", "key" to "min_players", "value" to arena.minimumPlayers)
        registry.messageService.send(sender, "info.line", "key" to "max_players", "value" to arena.maximumPlayers)
        registry.messageService.send(sender, "info.line", "key" to "countdown", "value" to arena.countdownSeconds)
        registry.messageService.send(sender, "info.line", "key" to "max_duration", "value" to arena.maximumDurationSeconds)
        registry.messageService.send(sender, "info.line", "key" to "waiting_room", "value" to (arena.waitingRoom?.let { "${it.worldName}@${it.x},${it.y},${it.z}" } ?: "—"))
        registry.messageService.send(sender, "info.line", "key" to "red_spawn", "value" to (arena.redSpawn?.let { "${it.worldName}@${it.x},${it.y},${it.z}" } ?: "—"))
        registry.messageService.send(sender, "info.line", "key" to "blue_spawn", "value" to (arena.blueSpawn?.let { "${it.worldName}@${it.x},${it.y},${it.z}" } ?: "—"))
        registry.messageService.send(sender, "info.line", "key" to "spectator", "value" to (arena.spectator?.let { "${it.worldName}@${it.x},${it.y},${it.z}" } ?: "—"))
        registry.messageService.send(sender, "info.line", "key" to "boundary", "value" to (arena.boundary?.let { "${it.minX},${it.minY},${it.minZ} → ${it.maxX},${it.maxY},${it.maxZ}" } ?: "—"))
        registry.messageService.send(sender, "info.line", "key" to "validation", "value" to if (result.isValid) "VALID" else result.errors.joinToString("; "))
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class EnableCommand : AdminSubCommand("enable") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val arena = registry.arenaService.setEnabled(args[0], true)
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "enable.success", "arena" to arena.id)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class DisableCommand : AdminSubCommand("disable") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val arena = registry.arenaService.setEnabled(args[0], false)
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "disable.success", "arena" to arena.id)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class SetLobbyCommand : AdminSubCommand("setlobby") {
    override val playerOnly = true
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as org.bukkit.entity.Player
        registry.lobbyService.set(player.location)
        registry.messageService.send(sender, "setlobby.success",
            "world" to player.world.name, "x" to player.location.x, "y" to player.location.y, "z" to player.location.z)
        return true
    }
}

class SetWaitingRoomCommand : AdminSubCommand("setwaitingroom") {
    override val playerOnly = true
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val player = sender as org.bukkit.entity.Player
        val arena = registry.arenaService.setWaitingRoom(args[0], player.location)
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "setwaitingroom.success", "arena" to arena.id)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class SetSpawnCommand : AdminSubCommand("setspawn") {
    override val playerOnly = true
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) return false
        val team = Team.fromString(args[1]) ?: throw IllegalArgumentException("Team must be 'red' or 'blue'.")
        val player = sender as org.bukkit.entity.Player
        val arena = registry.arenaService.setSpawn(args[0], team.id, player.location)
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "setspawn.success", "arena" to arena.id, "team" to team.id)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) }
            2 -> listOf("red", "blue").filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
    }
}

class SetSpectatorCommand : AdminSubCommand("setspectator") {
    override val playerOnly = true
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val player = sender as org.bukkit.entity.Player
        val arena = registry.arenaService.setSpectator(args[0], player.location)
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "setspectator.success", "arena" to arena.id)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class SetMinCommand : AdminSubCommand("setmin") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) return false
        val arena = registry.arenaService.setMinPlayers(args[0], args[1].toIntOrNull()
            ?: throw IllegalArgumentException("Amount must be an integer."))
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "setmin.success", "arena" to arena.id, "value" to arena.minimumPlayers)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class SetMaxCommand : AdminSubCommand("setmax") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) return false
        val arena = registry.arenaService.setMaxPlayers(args[0], args[1].toIntOrNull()
            ?: throw IllegalArgumentException("Amount must be an integer."))
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "setmax.success", "arena" to arena.id, "value" to arena.maximumPlayers)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class SetCountdownCommand : AdminSubCommand("setcountdown") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) return false
        val arena = registry.arenaService.setCountdownSeconds(args[0], args[1].toIntOrNull()
            ?: throw IllegalArgumentException("Seconds must be an integer."))
        if (arena == null) {
            registry.messageService.send(sender, "error.arena_not_found", "arena" to args[0])
        } else {
            registry.messageService.send(sender, "setcountdown.success", "arena" to arena.id, "value" to arena.countdownSeconds)
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class JoinCommand : PlayerSubCommand("join") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val player = sender as org.bukkit.entity.Player
        val result = registry.matchManager.join(player, args[0])
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            registry.messageService.send(sender, "join.failure", "msg" to (e?.message ?: "Unknown error"))
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas()
            .filter { it.enabled }
            .map { it.id }
            .filter { it.startsWith(args[0]) }
        else emptyList()
}

class LeaveCommand : PlayerSubCommand("leave") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as org.bukkit.entity.Player
        val result = registry.matchManager.leave(player)
        if (result.isFailure) {
            registry.messageService.send(sender, "leave.failure", "msg" to (result.exceptionOrNull()?.message ?: "Unknown"))
        } else {
            registry.messageService.send(sender, "leave.success")
        }
        return true
    }
}

class ForceStartCommand : AdminSubCommand("forcestart") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val result = registry.matchManager.forceStart(args[0])
        if (result.isFailure) {
            registry.messageService.send(sender, "forcestart.failure", "msg" to (result.exceptionOrNull()?.message ?: "Unknown"))
        } else {
            registry.messageService.send(sender, "forcestart.success", "arena" to args[0])
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class StopCommand : AdminSubCommand("stop") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val result = registry.matchManager.forceStop(args[0])
        if (result.isFailure) {
            registry.messageService.send(sender, "stop.failure", "msg" to (result.exceptionOrNull()?.message ?: "Unknown"))
        } else {
            registry.messageService.send(sender, "stop.success", "arena" to args[0])
        }
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> =
        if (args.size == 1) registry.arenaService.allArenas().map { it.id }.filter { it.startsWith(args[0]) } else emptyList()
}

class ReloadCommand : AdminSubCommand("reload") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        registry.configManager.reload()
        registry.arenaRepository.reload()
        registry.messageService.reload()
        registry.lobbyService.load()
        registry.messageService.send(sender, "reload.success")
        return true
    }
}

class StatsCommand : AdminSubCommand("stats") {
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        if (!registry.gameConfig.statistics.enabled) {
            registry.messageService.send(sender, "stats.disabled")
            return true
        }
        val targetName = args.firstOrNull()
        val playerId: java.util.UUID = if (targetName != null) {
            val offline = Bukkit.getOfflinePlayer(targetName)
            offline.uniqueId
        } else if (sender is org.bukkit.entity.Player) {
            sender.uniqueId
        } else {
            registry.messageService.send(sender, "error.player_only")
            return true
        }
        val stats = registry.statisticsService.fetchStats(playerId)
        registry.messageService.send(sender, "stats.header", "player" to (targetName ?: sender.name))
        registry.messageService.send(sender, "stats.line", "key" to "matches", "value" to stats.matchesPlayed)
        registry.messageService.send(sender, "stats.line", "key" to "wins", "value" to stats.wins)
        registry.messageService.send(sender, "stats.line", "key" to "losses", "value" to stats.losses)
        registry.messageService.send(sender, "stats.line", "key" to "draws", "value" to stats.draws)
        registry.messageService.send(sender, "stats.line", "key" to "eliminations", "value" to stats.eliminations)
        registry.messageService.send(sender, "stats.line", "key" to "hits", "value" to stats.hits)
        registry.messageService.send(sender, "stats.line", "key" to "shots", "value" to stats.shots)
        registry.messageService.send(sender, "stats.line", "key" to "accuracy", "value" to String.format("%.1f%%", stats.accuracy))
        registry.messageService.send(sender, "stats.line", "key" to "highest_combo", "value" to stats.highestCombo)
        registry.messageService.send(sender, "stats.line", "key" to "damage_dealt", "value" to stats.damageDealt)
        return true
    }
    override fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
