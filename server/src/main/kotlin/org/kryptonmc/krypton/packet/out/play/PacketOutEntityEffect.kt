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

import org.kryptonmc.krypton.network.buffer.BinaryReader
import org.kryptonmc.krypton.network.buffer.BinaryWriter
import org.kryptonmc.krypton.packet.Packet

/**
 * Clientbound Entity Effect (1.19.3 play, 0x68): tells the client an entity gained a status effect. In 1.19.3 the
 * effect id is a single byte (the classic mob-effect id, e.g. speed=1, regeneration=10). [flags] bits: 0x01 ambient,
 * 0x02 show particles, 0x04 show icon. The trailing boolean is "has factor data" — only the darkness effect carries
 * that NBT, so it is always written false here.
 */
@JvmRecord
data class PacketOutEntityEffect(
    val entityId: Int,
    val effectId: Int,
    val amplifier: Int,
    val duration: Int,
    val flags: Int
) : Packet {

    constructor(reader: BinaryReader) : this(reader.readVarInt(), reader.readByte().toInt(), reader.readByte().toInt(), reader.readVarInt(), reader.readByte().toInt())

    override fun write(writer: BinaryWriter) {
        writer.writeVarInt(entityId)
        writer.writeByte(effectId.toByte())
        writer.writeByte(amplifier.toByte())
        writer.writeVarInt(duration)
        writer.writeByte(flags.toByte())
        writer.writeBoolean(false) // no factor data (only the darkness effect uses it)
    }
}
