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
package org.kryptonmc.krypton.packet.out.play

import org.kryptonmc.api.resource.ResourceKey
import org.kryptonmc.api.resource.ResourceKeys
import org.kryptonmc.api.world.GameMode
import org.kryptonmc.api.world.World
import org.kryptonmc.api.world.dimension.DimensionType
import org.kryptonmc.krypton.packet.Packet
import org.kryptonmc.krypton.util.enumhelper.GameModes
import org.kryptonmc.krypton.network.buffer.BinaryReader
import org.kryptonmc.krypton.network.buffer.BinaryWriter

/**
 * Clientbound Respawn (1.19.3, protocol 761). Tells the client to discard its current world and load a fresh one —
 * used after death so the death screen dismisses. The server must follow this with chunk data and a position sync.
 *
 * [dataKept] is a bitmask: 0x01 keep attributes, 0x02 keep metadata. A death respawn keeps nothing (0).
 */
@JvmRecord
data class PacketOutRespawn(
    val dimensionType: ResourceKey<DimensionType>,
    val dimension: ResourceKey<World>,
    val seed: Long,
    val gameMode: GameMode,
    val oldGameMode: GameMode?,
    val isDebug: Boolean,
    val isFlat: Boolean,
    val dataKept: Byte
) : Packet {

    constructor(reader: BinaryReader) : this(
        ResourceKey.of(ResourceKeys.DIMENSION_TYPE, reader.readKey()),
        ResourceKey.of(ResourceKeys.DIMENSION, reader.readKey()),
        reader.readLong(),
        GameModes.fromId(reader.readByte().toInt())!!,
        reader.readByte().toInt().let { if (it < 0) null else GameModes.fromId(it) },
        reader.readBoolean(),
        reader.readBoolean(),
        reader.readByte()
    )

    override fun write(writer: BinaryWriter) {
        writer.writeResourceKey(dimensionType)
        writer.writeResourceKey(dimension)
        writer.writeLong(seed)
        writer.writeByte(gameMode.ordinal.toByte())
        writer.writeByte((oldGameMode?.ordinal ?: -1).toByte())
        writer.writeBoolean(isDebug)
        writer.writeBoolean(isFlat)
        writer.writeByte(dataKept)
    }
}
