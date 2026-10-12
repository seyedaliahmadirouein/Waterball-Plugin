package com.example.waterballonz.arena

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection

/**
 * A persisted location: world name + x, y, z, yaw, pitch.
 *
 * This data class is independent of the Bukkit [Location] so that arenas
 * survive world unloads and server restarts. Use [resolve] to obtain a live
 * Bukkit [Location] when needed.
 */
data class StoredLocation(
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun resolve(): Location? {
        val world: World = Bukkit.getWorld(worldName) ?: return null
        return Location(world, x, y, z, yaw, pitch)
    }

    fun isWorldAvailable(): Boolean = Bukkit.getWorld(worldName) != null

    fun serialize(section: ConfigurationSection) {
        section["world"] = worldName
        section["x"] = x
        section["y"] = y
        section["z"] = z
        section["yaw"] = yaw
        section["pitch"] = pitch
    }

    companion object {
        fun from(section: ConfigurationSection): StoredLocation? {
            val world = section.getString("world") ?: return null
            return StoredLocation(
                worldName = world,
                x = section.getDouble("x"),
                y = section.getDouble("y"),
                z = section.getDouble("z"),
                yaw = section.getDouble("yaw").toFloat(),
                pitch = section.getDouble("pitch").toFloat(),
            )
        }

        fun from(location: Location): StoredLocation? {
            val worldName = location.world?.name ?: return null
            return StoredLocation(
                worldName = worldName,
                x = location.x,
                y = location.y,
                z = location.z,
                yaw = location.yaw,
                pitch = location.pitch,
            )
        }
    }
}
