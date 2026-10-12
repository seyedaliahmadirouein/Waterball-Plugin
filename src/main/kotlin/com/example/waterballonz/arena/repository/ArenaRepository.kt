package com.example.waterballonz.arena.repository

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.arena.ArenaBoundary
import com.example.waterballonz.arena.ArenaDefinition
import com.example.waterballonz.arena.StoredLocation
import com.example.waterballonz.configuration.ConfigManager
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException

/**
 * Reads and writes arena definitions to `arenas.yml`.
 *
 * The file is rewritten fully on every save. The arena list itself is
 * immutable from the consumer's perspective — to mutate an arena, callers
 * must use [update].
 */
class ArenaRepository(
    private val plugin: WaterBallonZPlugin,
    private val configManager: ConfigManager,
) {
    private val file: File get() = File(plugin.dataFolder, "arenas.yml")

    private val arenas: MutableMap<String, ArenaDefinition> = linkedMapOf()

    @Synchronized
    fun reload() {
        if (!file.exists()) {
            plugin.saveResource("arenas.yml", false)
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        arenas.clear()

        val section = yaml.getConfigurationSection("arenas")
        if (section != null) {
            for (key in section.getKeys(false)) {
                val arenaSection = section.getConfigurationSection(key) ?: continue
                val def = loadArena(key, arenaSection)
                if (def != null) arenas[key] = def
                else plugin.logger.warning("[WaterBallonZ] Failed to load arena '$key' from arenas.yml")
            }
        }
    }

    @Synchronized
    fun all(): List<ArenaDefinition> = arenas.values.toList()

    @Synchronized
    fun get(id: String): ArenaDefinition? = arenas[id.lowercase()]

    @Synchronized
    fun exists(id: String): Boolean = arenas.containsKey(id.lowercase())

    @Synchronized
    fun create(id: String): ArenaDefinition {
        require(!exists(id)) { "Arena '$id' already exists." }
        val arena = ArenaDefinition(id = id.lowercase())
        arenas[id.lowercase()] = arena
        save()
        return arena
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!exists(id)) return false
        arenas.remove(id.lowercase())
        save()
        return true
    }

    @Synchronized
    fun update(arena: ArenaDefinition) {
        arenas[arena.id.lowercase()] = arena
        save()
    }

    @Synchronized
    fun save() {
        val yaml = YamlConfiguration()
        val section = yaml.createSection("arenas")
        for ((id, def) in arenas) {
            val arenaSection = section.createSection(id)
            writeArena(arenaSection, def)
        }
        try {
            yaml.save(file)
        } catch (e: IOException) {
            plugin.logger.severe("[WaterBallonZ] Failed to save arenas.yml: ${e.message}")
        }
    }

    private fun loadArena(id: String, section: ConfigurationSection): ArenaDefinition? {
        return try {
            ArenaDefinition(
                id = id,
                enabled = section.getBoolean("enabled", false),
                minimumPlayers = section.getInt("minimum-players", 2),
                maximumPlayers = section.getInt("maximum-players", 16),
                countdownSeconds = section.getInt("countdown-seconds", 3),
                maximumDurationSeconds = section.getInt("maximum-duration-seconds", 600),
                waitingRoom = section.getConfigurationSection("locations.waiting-room")?.let(StoredLocation::from),
                redSpawn = section.getConfigurationSection("locations.red-spawn")?.let(StoredLocation::from),
                blueSpawn = section.getConfigurationSection("locations.blue-spawn")?.let(StoredLocation::from),
                spectator = section.getConfigurationSection("locations.spectator")?.let(StoredLocation::from),
                boundary = section.getConfigurationSection("boundary")?.let { loadBoundary(it) },
                teamBalanceStrategy = section.getString("team-balance-strategy", "auto") ?: "auto",
                victoryMessage = section.getString("victory-message"),
                drawMessage = section.getString("draw-message"),
                autoRestart = section.getBoolean("auto-restart", false),
            )
        } catch (e: Exception) {
            plugin.logger.warning("[WaterBallonZ] Error loading arena $id: ${e.message}")
            null
        }
    }

    private fun writeArena(section: ConfigurationSection, arena: ArenaDefinition) {
        section["enabled"] = arena.enabled
        section["minimum-players"] = arena.minimumPlayers
        section["maximum-players"] = arena.maximumPlayers
        section["countdown-seconds"] = arena.countdownSeconds
        section["maximum-duration-seconds"] = arena.maximumDurationSeconds
        section["team-balance-strategy"] = arena.teamBalanceStrategy
        section["auto-restart"] = arena.autoRestart
        arena.victoryMessage?.let { section["victory-message"] = it }
        arena.drawMessage?.let { section["draw-message"] = it }

        val locations = section.createSection("locations")
        arena.waitingRoom?.let { it.serialize(locations.createSection("waiting-room")) }
        arena.redSpawn?.let { it.serialize(locations.createSection("red-spawn")) }
        arena.blueSpawn?.let { it.serialize(locations.createSection("blue-spawn")) }
        arena.spectator?.let { it.serialize(locations.createSection("spectator")) }

        arena.boundary?.let { b ->
            val bound = section.createSection("boundary")
            bound["world"] = b.worldName
            bound["min-x"] = b.minX; bound["min-y"] = b.minY; bound["min-z"] = b.minZ
            bound["max-x"] = b.maxX; bound["max-y"] = b.maxY; bound["max-z"] = b.maxZ
        }
    }

    private fun loadBoundary(section: ConfigurationSection): ArenaBoundary? {
        val world = section.getString("world") ?: return null
        return ArenaBoundary(
            worldName = world,
            minX = section.getDouble("min-x"),
            minY = section.getDouble("min-y"),
            minZ = section.getDouble("min-z"),
            maxX = section.getDouble("max-x"),
            maxY = section.getDouble("max-y"),
            maxZ = section.getDouble("max-z"),
        )
    }
}
