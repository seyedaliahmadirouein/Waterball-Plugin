package com.example.waterballonz.commands

import com.example.waterballonz.bootstrap.ServiceRegistry
import org.bukkit.command.CommandSender

class HelpCommand : SubCommand {
    override val name: String = "help"
    override fun hasPermission(sender: CommandSender): Boolean = true
    override fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean {
        registry.messageService.send(sender, "help.header")
        listOf(
            "help" to "Show this help.",
            "create <arena>" to "Create a new arena (admin).",
            "delete <arena> confirm" to "Delete an arena (admin).",
            "list" to "List all arenas.",
            "info <arena>" to "Show details for an arena (admin).",
            "enable <arena>" to "Enable an arena (admin).",
            "disable <arena>" to "Disable an arena (admin).",
            "setlobby" to "Set the global lobby to your location (admin).",
            "setwaitingroom <arena>" to "Set an arena's waiting room (admin).",
            "setspawn <arena> <red|blue>" to "Set an arena's team spawn (admin).",
            "setspectator <arena>" to "Set an arena's spectator spawn (admin).",
            "setmin <arena> <amount>" to "Set an arena's minimum player count (admin).",
            "setmax <arena> <amount>" to "Set an arena's maximum player count (admin).",
            "join <arena>" to "Join an arena's waiting room.",
            "leave" to "Leave your current WaterBallonZ session.",
            "forcestart <arena>" to "Force-start an arena (admin).",
            "stop <arena>" to "Force-stop an arena's match (admin).",
            "reload" to "Reload configuration files (admin).",
            "stats [player]" to "View statistics.",
        ).forEach { (cmd, desc) -> registry.messageService.send(sender, "help.line", "cmd" to cmd, "desc" to desc) }
        return true
    }
}
