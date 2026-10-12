package com.example.waterballonz.commands

import com.example.waterballonz.arena.ArenaState
import com.example.waterballonz.arena.ArenaValidator
import com.example.waterballonz.bootstrap.ServiceRegistry
import com.example.waterballonz.team.Team
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * Top-level command for `/waterballonz` (aliased `/wbz`).
 *
 * All sub-commands route through [SubCommand] implementations. Each handles
 * its own permission check, argument validation, and tab completion.
 */
class WaterBallonZCommand(private val registry: ServiceRegistry) : CommandExecutor, TabCompleter {

    private val subcommands: List<SubCommand> = listOf(
        HelpCommand(),
        CreateCommand(),
        DeleteCommand(),
        ListCommand(),
        InfoCommand(),
        EnableCommand(),
        DisableCommand(),
        SetLobbyCommand(),
        SetWaitingRoomCommand(),
        SetSpawnCommand(),
        SetSpectatorCommand(),
        SetMinCommand(),
        SetMaxCommand(),
        SetCountdownCommand(),
        JoinCommand(),
        LeaveCommand(),
        ForceStartCommand(),
        StopCommand(),
        ReloadCommand(),
        StatsCommand(),
    )

    private val byName: Map<String, SubCommand> = subcommands.associateBy { it.name }

    fun register() {
        val plugin = registry.plugin
        val cmd: PluginCommand? = plugin.getCommand("waterballonz")
        if (cmd == null) {
            plugin.logger.severe("[WaterBallonZ] Root command 'waterballonz' missing from plugin.yml — commands will not work.")
            return
        }
        cmd.setExecutor(this)
        cmd.tabCompleter = this
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            HelpCommand().execute(registry, sender, emptyArray())
            return true
        }
        val subName = args[0].lowercase()
        val sub = byName[subName]
        if (sub == null) {
            registry.messageService.send(sender, "error.unknown_subcommand", "name" to subName)
            return true
        }
        if (!sub.hasPermission(sender)) {
            registry.messageService.send(sender, "error.permission")
            return true
        }
        if (sub.playerOnly && sender !is Player) {
            registry.messageService.send(sender, "error.player_only")
            return true
        }
        val subArgs = args.copyOfRange(1, args.size)
        try {
            if (!sub.execute(registry, sender, subArgs)) {
                registry.messageService.send(sender, "command.usage." + sub.name)
            }
        } catch (e: IllegalArgumentException) {
            registry.messageService.send(sender, "error.invalid_argument", "msg" to e.message)
        } catch (e: IllegalStateException) {
            registry.messageService.send(sender, "error.invalid_state", "msg" to e.message)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        // When the user has typed just "/wbz" or "/wbz " (still on the first
        // argument slot), suggest every subcommand they have permission for.
        // This makes the suggestion list appear immediately on the very
        // first TAB press, instead of waiting for the user to type a letter.
        if (args.size <= 1) {
            val prefix = args.firstOrNull()?.lowercase() ?: ""
            return subcommands
                .filter { it.hasPermission(sender) }
                .map { it.name }
                .filter { it.startsWith(prefix) }
                .sorted()
        }
        // User has typed "/wbz <subcommand> ..." — delegate to the subcommand.
        val subName = args[0].lowercase()
        val sub = byName[subName] ?: return emptyList()
        if (!sub.hasPermission(sender)) return emptyList()
        return sub.tabComplete(registry, sender, args.copyOfRange(1, args.size))
    }
}
