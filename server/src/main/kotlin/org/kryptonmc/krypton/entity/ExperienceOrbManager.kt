/*
 * This file is part of the Krypton project, licensed under the Apache License v2.0
 *
 * Copyright (C) 2021-2023 KryptonMC and the contributors of the Krypton project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kryptonmc.krypton.entity

import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.packet.out.play.PacketOutSetExperience
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks experience orbs ([KryptonExperienceOrb]) and lets a nearby player collect them, accumulating XP and
 * pushing the player's bar/level to the client via [PacketOutSetExperience]. Driven by the shared container tick.
 * Per-player totals are persisted to a side file so XP survives a server restart.
 */
object ExperienceOrbManager {

    private const val PICKUP_RADIUS_SQUARED = 16.0 // orbs are collected from a bit further than dropped items
    private const val XP_PER_LEVEL = 10            // simplified: every 10 XP is one level
    private val FILE: Path = Path.of("world", "krypton-xp.dat")
    private var loaded = false

    private val orbs = CopyOnWriteArrayList<KryptonExperienceOrb>()
    private val totalXp = HashMap<UUID, Int>()     // accumulated XP per player (persisted)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Files.exists(FILE)) return
        try {
            for (line in Files.readAllLines(FILE)) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val uuid = runCatching { UUID.fromString(line.substring(0, eq)) }.getOrNull() ?: continue
                val xp = line.substring(eq + 1).trim().toIntOrNull() ?: continue
                totalXp[uuid] = xp
            }
        } catch (ignored: Exception) {
            // corrupt/unreadable file — start fresh rather than crash
        }
    }

    private fun save() {
        try {
            Files.write(FILE, totalXp.entries.map { "${it.key}=${it.value}" })
        } catch (ignored: Exception) {
        }
    }

    fun add(orb: KryptonExperienceOrb) {
        orbs.add(orb)
    }

    /** Sends the player their persisted XP when they join, so the bar/level reflect saved progress. */
    fun sendInitial(player: KryptonPlayer) {
        ensureLoaded()
        sendExperience(player, totalXp[player.uuid] ?: 0)
    }

    fun tick() {
        ensureLoaded()
        orbs.removeIf { it.isRemoved() }
        for (orb in orbs) {
            if (orb.isRemoved()) continue
            val collector = orb.world.players.firstOrNull {
                it.position.distanceSquared(orb.position) <= PICKUP_RADIUS_SQUARED
            } ?: continue
            val total = totalXp.merge(collector.uuid, orb.experience.coerceAtLeast(1)) { a, b -> a + b }!!
            sendExperience(collector, total)
            save() // persist so XP survives a restart
            orb.remove()
        }
    }

    private fun sendExperience(player: KryptonPlayer, total: Int) {
        val level = total / XP_PER_LEVEL
        val bar = (total % XP_PER_LEVEL).toFloat() / XP_PER_LEVEL
        player.connection.send(PacketOutSetExperience(bar, level, total))
    }
}
