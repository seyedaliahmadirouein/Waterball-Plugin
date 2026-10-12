package com.example.waterballonz.scheduler

import com.example.waterballonz.WaterBallonZPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Thin abstraction over Bukkit's scheduler.
 *
 * Currently delegates to the standard Paper/Bukkit scheduler. If Folia support
 * is added later, this is the only class that needs to be re-implemented.
 *
 * All scheduled work is owned by the plugin so that [shutdownAll] can clean
 * up everything during plugin disable.
 */
class WbzScheduler(private val plugin: WaterBallonZPlugin) {

    private val tasks: MutableSet<BukkitTask> = java.util.Collections.synchronizedSet(HashSet())

    fun runRepeating(delayTicks: Long, periodTicks: Long, action: () -> Unit): BukkitTask {
        val task = plugin.server.scheduler.runTaskTimer(plugin, action, delayTicks, periodTicks)
        tasks.add(task)
        return task
    }

    fun runDelayed(delayTicks: Long, action: () -> Unit): BukkitTask {
        val task = plugin.server.scheduler.runTaskLater(plugin, action, delayTicks)
        tasks.add(task)
        return task
    }

    fun runNow(action: () -> Unit): BukkitTask {
        val task = plugin.server.scheduler.runTask(plugin, action)
        tasks.add(task)
        return task
    }

    fun runAsync(action: () -> Unit): BukkitTask {
        val task = plugin.server.scheduler.runTaskAsynchronously(plugin, action)
        tasks.add(task)
        return task
    }

    fun cancel(task: BukkitTask) {
        task.cancel()
        tasks.remove(task)
    }

    fun shutdownAll() {
        synchronized(tasks) {
            tasks.forEach { it.cancel() }
            tasks.clear()
        }
    }
}
