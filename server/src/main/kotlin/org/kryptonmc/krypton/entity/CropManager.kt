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

import org.apache.logging.log4j.LogManager
import org.kryptonmc.api.util.Vec3i
import org.kryptonmc.krypton.coordinate.ChunkPos
import org.kryptonmc.krypton.network.PacketGrouping
import org.kryptonmc.krypton.packet.out.play.PacketOutBlockUpdate
import org.kryptonmc.krypton.registry.KryptonRegistries
import org.kryptonmc.krypton.state.property.KryptonProperties
import org.kryptonmc.krypton.world.KryptonWorld
import org.kryptonmc.krypton.world.block.state.KryptonBlockState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks player-planted crops (wheat/carrots/potatoes) and advances their block `age` (0..[MAX_AGE]) over time,
 * driven from the shared ~1s tick (see `PlayPacketHandler.ensureContainerTick`). Every [GROWTH_CYCLES] cycles each
 * tracked crop grows one stage and is re-broadcast; at full age — or once the block is no longer a crop (harvested
 * or replaced) — it's dropped from tracking. Growth speed is overridable for fast tests: -Dkrypton.cropGrowthCycles=<n>.
 */
object CropManager {

    const val MAX_AGE: Int = 7
    private val GROWTH_CYCLES = System.getProperty("krypton.cropGrowthCycles")?.toIntOrNull() ?: 5
    private val CROP_KEYS = setOf("wheat", "carrots", "potatoes")

    private val crops = CopyOnWriteArrayList<Pair<KryptonWorld, Vec3i>>()
    private val LOGGER = LogManager.getLogger()
    private var counter = 0

    private fun keyOf(state: KryptonBlockState): String = KryptonRegistries.BLOCK.getKey(state.block).value()

    /** Registers [pos] as a growing crop if [state] is one we age. Returns true if it was tracked. */
    fun register(world: KryptonWorld, pos: Vec3i, state: KryptonBlockState): Boolean {
        if (keyOf(state) !in CROP_KEYS) return false
        val entry = world to pos
        if (entry !in crops) crops.add(entry)
        return true
    }

    fun tick() {
        if (crops.isEmpty()) return
        if (++counter < GROWTH_CYCLES) return
        counter = 0
        for (entry in crops) {
            val (world, pos) = entry
            val state = world.getBlock(pos.x, pos.y, pos.z)
            if (keyOf(state) !in CROP_KEYS) { crops.remove(entry); continue } // harvested or replaced
            val age = state.requireProperty(KryptonProperties.AGE_7)
            if (age >= MAX_AGE) { crops.remove(entry); continue } // already fully grown
            val grown = state.setProperty(KryptonProperties.AGE_7, age + 1)
            world.chunkManager.getChunk(ChunkPos(pos.x shr 4, pos.z shr 4))?.setBlock(pos, grown, false)
            PacketGrouping.sendGroupedPacket(world.players, PacketOutBlockUpdate(pos, grown))
            if (age + 1 >= MAX_AGE) { crops.remove(entry); LOGGER.info("Crop at $pos reached full growth") }
        }
    }
}
