package com.example.waterballonz.team

/**
 * The two competing teams in WaterBallonZ.
 *
 * The ordering is significant: [entryOrNull] is used by the [TeamBalancer] to
 * alternate the first assignment when deterministic balancing is enabled.
 */
enum class Team(
    val id: String,
    val displayName: String,
    val colorCode: String,
    val miniMessageColor: String,
) {
    RED("red", "Red", "&c", "<red>"),
    BLUE("blue", "Blue", "&9", "<blue>");

    fun opposite(): Team = if (this == RED) BLUE else RED

    companion object {
        fun fromString(value: String?): Team? =
            value?.lowercase()?.let { v ->
                entries.firstOrNull { it.id == v }
            }
    }
}
