package com.example.waterballonz.commands

import com.example.waterballonz.bootstrap.ServiceRegistry
import org.bukkit.command.CommandSender

/**
 * Base interface for `/waterballonz <name>` sub-commands.
 */
interface SubCommand {
    val name: String
    val playerOnly: Boolean get() = false

    fun hasPermission(sender: CommandSender): Boolean

    /** @return `true` if the command was handled successfully. */
    fun execute(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): Boolean

    fun tabComplete(registry: ServiceRegistry, sender: CommandSender, args: Array<out String>): List<String> = emptyList()
}

abstract class AdminSubCommand(override val name: String) : SubCommand {
    override fun hasPermission(sender: CommandSender): Boolean =
        sender.hasPermission("waterballonz.admin.${name}") ||
            sender.hasPermission("waterballonz.admin") ||
            sender.hasPermission("waterballonz.*")
}

abstract class PlayerSubCommand(override val name: String) : SubCommand {
    override val playerOnly: Boolean = true
    override fun hasPermission(sender: CommandSender): Boolean =
        sender.hasPermission("waterballonz.${name}") ||
            sender.hasPermission("waterballonz.play") ||
            sender.hasPermission("waterballonz.*")
}
