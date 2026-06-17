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

import org.kryptonmc.api.util.Vec3i
import org.kryptonmc.krypton.coordinate.ChunkPos
import org.kryptonmc.krypton.network.PacketGrouping
import org.kryptonmc.krypton.packet.out.play.PacketOutBlockUpdate
import org.kryptonmc.krypton.registry.KryptonRegistries
import org.kryptonmc.krypton.world.KryptonWorld
import org.kryptonmc.krypton.world.block.KryptonBlocks
import kotlin.math.floor

/**
 * Primed (lit) TNT: a real entity that falls under gravity for a short fuse, then explodes — clearing every block
 * within [BLAST_RADIUS] (spherical, except bedrock/obsidian) to air — and removes itself. Spawned by lighting a TNT
 * block. Simplified: fixed radius with no resistance falloff, no entity damage/knockback, no fire.
 */
class KryptonPrimedTnt(world: KryptonWorld) : KryptonEntity(world) {

    override val type: KryptonEntityType<KryptonEntity>
        get() = KryptonEntityTypes.PRIMED_TNT

    private var fuse = FUSE_TICKS

    override fun tick(time: Long) {
        super.tick(time)
        if (isRemoved()) return
        fallToGround() // the primed TNT falls until it rests on the ground
        if (--fuse <= 0) {
            explode()
            remove()
        }
    }

    private fun explode() {
        val air = KryptonBlocks.AIR.defaultState
        val cx = floor(position.x).toInt()
        val cy = floor(position.y).toInt()
        val cz = floor(position.z).toInt()
        for (dx in -BLAST_RADIUS..BLAST_RADIUS) for (dy in -BLAST_RADIUS..BLAST_RADIUS) for (dz in -BLAST_RADIUS..BLAST_RADIUS) {
            if (dx * dx + dy * dy + dz * dz > BLAST_RADIUS * BLAST_RADIUS) continue // spherical blast
            val pos = Vec3i(cx + dx, cy + dy, cz + dz)
            val block = world.getBlock(pos.x, pos.y, pos.z)
            if (block.eq(KryptonBlocks.AIR)) continue
            val key = KryptonRegistries.BLOCK.getKey(block.block).value()
            if (key == "bedrock" || key == "obsidian") continue // blast-resistant
            world.chunkManager.getChunk(ChunkPos(pos.x shr 4, pos.z shr 4))?.setBlock(pos, air, false)
            PacketGrouping.sendGroupedPacket(world.players, PacketOutBlockUpdate(pos, air))
        }
    }

    companion object {

        private val FUSE_TICKS = System.getProperty("krypton.tntFuseTicks")?.toIntOrNull() ?: 80 // fuse before the blast (test hook)
        private const val BLAST_RADIUS = 3
    }
}
