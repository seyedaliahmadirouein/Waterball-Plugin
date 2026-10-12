package com.example.waterballonz.lobby

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.StoredLocation
import com.example.waterballonz.configuration.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

/**
 * Manages the global lobby location.
 *
 * The lobby is the default return destination for players leaving a match or
 * being eliminated. When `general.lobby.enabled` is `false`, players are sent
 * to the world spawn instead.
 *
 * The location is stored in `lobby.yml` under the `data/` folder.
 */
class LobbyService(
    private val plugin: WaterBallonZPlugin,
    private val configManager: ConfigManager,
) {
    private val file: File get() = File(plugin.dataFolder, "lobby.yml")

    private var stored: StoredLocation? = null

    fun load() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getConfigurationSection("lobby")?.let { section ->
            stored = StoredLocation.from(section)
        }
    }

    fun isSet(): Boolean = stored != null

    fun set(location: Location): Boolean {
        val stored = StoredLocation.from(location) ?: return false
        this.stored = stored
        save()
        return true
    }

    fun stored(): StoredLocation? = stored

    /**
     * Resolve the configured lobby location, falling back to the world spawn
     * if `general.lobby.fallback-to-spawn` is enabled.
     */
    fun resolve(): Location? {
        val config = plugin.registry.gameConfig
        stored?.resolve()?.let { return it }
        if (!config.general.lobby.fallbackToSpawn) return null
        val world = Bukkit.getWorlds().firstOrNull() ?: return null
        return world.spawnLocation
    }

    /**
     * Send [player] to the lobby (or fallback) using the configured precedence:
     *
     *   1. Explicit per-session return location (saved by MatchManager).
     *   2. Configured global lobby.
     *   3. Safe world-spawn fallback.
     */
    fun sendToLobby(player: Player, returnOverride: Location? = null): Boolean {
        val target = returnOverride ?: resolve()
        if (target == null) {
            plugin.logger.warning("[WaterBallonZ] No valid lobby location for ${player.name}.")
            return false
        }
        return player.teleport(target)
    }

    private fun save() {
        val yaml = YamlConfiguration()
        val stored = this.stored
        if (stored != null) {
            stored.serialize(yaml.createSection("lobby"))
        }
        yaml.save(file)
    }
}
