package com.example.waterballonz.items

import com.example.waterballonz.WaterBallonZPlugin
import com.example.waterballonz.configuration.GameConfig
import com.example.waterballonz.util.LegacyColor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Factory and validator for the custom WaterBall item.
 *
 * Authentic WaterBalls are identified by a PersistentDataContainer marker
 * under the [markerKey] namespaced key. The visible display name, material,
 * and lore are never used to authenticate — only the marker.
 *
 * NOTE: Item display name and lore are set via the legacy `§`-coloured
 * string methods (`setDisplayName(String)`, `setLore(List<String>)`)
 * rather than the Adventure `displayName(Component)` / `lore(List<Component>)`
 * overloads. The Adventure overloads exist on Paper's ItemMeta but are not
 * declared on Spigot's runtime ItemMeta interface, so calling them would
 * throw `NoSuchMethodError`. The string-based methods exist on both.
 */
class WaterBallItem(
    private val plugin: WaterBallonZPlugin,
    private val config: GameConfig,
) {
    val markerKey: NamespacedKey = NamespacedKey(plugin, "waterball_marker")
    val arenaBindingKey: NamespacedKey = NamespacedKey(plugin, "waterball_arena")
    val matchIdKey: NamespacedKey = NamespacedKey(plugin, "waterball_match_id")

    private val mini = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.legacySection()

    val material: Material by lazy {
        Material.matchMaterial(config.waterball.itemMaterial)
            ?: Material.SNOWBALL
    }

    fun create(amount: Int = 1, arenaId: String? = null, matchId: Long? = null): ItemStack {
        require(amount in 1..config.waterball.maximumHeld) {
            "Amount $amount out of range."
        }
        val stack = ItemStack(material, amount)
        val meta = stack.itemMeta ?: return stack

        // Use the legacy string methods (work on both Spigot and Paper).
        meta.setDisplayName(toLegacyString(config.waterball.displayName))

        if (config.waterball.lore.isNotEmpty()) {
            meta.lore = config.waterball.lore.map { toLegacyString(it) }
        }

        val pdc: PersistentDataContainer = meta.persistentDataContainer
        pdc.set(markerKey, PersistentDataType.BYTE, 1.toByte())
        arenaId?.let { pdc.set(arenaBindingKey, PersistentDataType.STRING, it) }
        matchId?.let { pdc.set(matchIdKey, PersistentDataType.LONG, it) }

        if (config.waterball.customModelData > 0) {
            meta.setCustomModelData(config.waterball.customModelData)
        }

        if (config.waterball.enchantedGlint) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

        stack.itemMeta = meta
        return stack
    }

    fun isWaterBall(stack: ItemStack?): Boolean {
        if (stack == null || stack.type != material) return false
        val meta = stack.itemMeta ?: return false
        return meta.persistentDataContainer.has(markerKey, PersistentDataType.BYTE)
    }

    fun arenaIdOf(stack: ItemStack?): String? {
        if (stack == null) return null
        val meta = stack.itemMeta ?: return null
        return meta.persistentDataContainer.get(arenaBindingKey, PersistentDataType.STRING)
    }

    fun matchIdOf(stack: ItemStack?): Long? {
        if (stack == null) return null
        val meta = stack.itemMeta ?: return null
        return meta.persistentDataContainer.get(matchIdKey, PersistentDataType.LONG)
    }

    fun displayName(): Component =
        mini.deserialize(LegacyColor.toMiniMessage(config.waterball.displayName))

    /** Convert a (possibly legacy-coded) message string into a §-coloured legacy string. */
    private fun toLegacyString(text: String): String {
        val component = mini.deserialize(LegacyColor.toMiniMessage(text))
        return legacySerializer.serialize(component)
    }
}

