package com.example.waterballonz.arena

/**
 * Optional cuboid arena boundary.
 *
 * When configured, players who leave the boundary are teleported back to their
 * spawn (or eliminated, depending on the configured corrective action — that
 * is enforced by the match service).
 */
data class ArenaBoundary(
    val worldName: String,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    fun contains(world: String, x: Double, y: Double, z: Double): Boolean {
        if (world != worldName) return false
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (minX > maxX) errors += "boundary: minX ($minX) greater than maxX ($maxX)."
        if (minY > maxY) errors += "boundary: minY ($minY) greater than maxY ($maxY)."
        if (minZ > maxZ) errors += "boundary: minZ ($minZ) greater than maxZ ($maxZ)."
        return errors
    }
}
