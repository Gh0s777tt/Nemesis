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

import org.kryptonmc.api.util.Position
import org.kryptonmc.krypton.world.KryptonWorld
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

/**
 * Basic natural mob spawning, driven once per server tick from [KryptonWorld.tick].
 *
 * Every [spawnInterval] ticks it makes a few attempts per player to place a mob on valid ground in a ring
 * around them: passive animals during the day, hostile mobs at night (by `dayTime`). Spawns are capped at
 * [MOB_CAP] live naturally-spawned mobs and only happen in already-loaded chunks. The interval is overridable
 * via `-Dkrypton.spawnIntervalTicks=<n>` (the spawn bot test uses a small value to see spawns quickly).
 */
object NaturalSpawner {

    private val spawnInterval = System.getProperty("krypton.spawnIntervalTicks")?.toIntOrNull() ?: 200
    private const val MOB_CAP = 20
    private const val ATTEMPTS_PER_PLAYER = 5
    private const val MIN_RADIUS = 6
    private const val MAX_RADIUS = 16
    private const val GROUND_SCAN_DEPTH = 12

    private val PASSIVE = listOf("cow", "pig", "sheep", "chicken")
    private val HOSTILE = listOf("zombie")

    private val spawned = CopyOnWriteArrayList<KryptonEntity>()
    private var counter = 0

    fun tick(world: KryptonWorld) {
        spawned.removeIf { it.isRemoved() }
        if (++counter < spawnInterval) return
        counter = 0
        if (spawned.size >= MOB_CAP) return
        val players = world.players
        if (players.isEmpty()) return

        val pool = if (isNight(world)) HOSTILE else PASSIVE
        for (player in players) {
            repeat(ATTEMPTS_PER_PLAYER) {
                if (spawned.size >= MOB_CAP) return
                val position = randomGroundNear(world, player.position) ?: return@repeat
                val mobId = pool[world.random.nextInt(pool.size)]
                val entity = EntityFactory.create(world, mobId, null) ?: return@repeat
                entity.position = position
                world.spawnEntity(entity)
                spawned.add(entity)
            }
        }
    }

    /** Picks a random column in the [MIN_RADIUS]..[MAX_RADIUS] ring and returns a standable spot, or null. */
    private fun randomGroundNear(world: KryptonWorld, origin: Position): Position? {
        val signX = if (world.random.nextBoolean()) 1 else -1
        val signZ = if (world.random.nextBoolean()) 1 else -1
        val bx = floor(origin.x).toInt() + signX * (MIN_RADIUS + world.random.nextInt(MAX_RADIUS - MIN_RADIUS + 1))
        val bz = floor(origin.z).toInt() + signZ * (MIN_RADIUS + world.random.nextInt(MAX_RADIUS - MIN_RADIUS + 1))

        // Only spawn in chunks that are already loaded around the player.
        if (world.getChunk(bx shr 4, bz shr 4) == null) return null

        // Scan downward for solid ground (not liquid) with two passable blocks above it (room for the mob).
        val startY = floor(origin.y).toInt() + 2
        var y = startY
        val minY = startY - GROUND_SCAN_DEPTH
        while (y > minY) {
            val ground = world.getBlock(bx, y - 1, bz)
            val feet = world.getBlock(bx, y, bz)
            val head = world.getBlock(bx, y + 1, bz)
            if (ground.isSolid() && !ground.isLiquid() && !feet.isSolid() && !head.isSolid()) {
                return Position(bx + 0.5, y.toDouble(), bz + 0.5, 0F, 0F)
            }
            y--
        }
        return null
    }

    private fun isNight(world: KryptonWorld): Boolean {
        val time = world.data.dayTime % 24000L
        return time in 13000L..22000L
    }
}
