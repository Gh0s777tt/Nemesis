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
package org.kryptonmc.krypton.packet.`in`.play

import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.network.buffer.BinaryReader
import org.kryptonmc.krypton.network.buffer.BinaryWriter
import org.kryptonmc.krypton.network.handlers.PlayPacketHandler
import org.kryptonmc.krypton.packet.InboundPacket

/**
 * Sent by the client when a player clicks a slot in a container window (window 0 being the player's own inventory).
 */
@JvmRecord
data class PacketInClickContainer(
    val containerId: Byte,
    val stateId: Int,
    val slot: Short,
    val button: Byte,
    val mode: Int,
    val changedSlots: Map<Short, KryptonItemStack>,
    val carriedItem: KryptonItemStack
) : InboundPacket<PlayPacketHandler> {

    constructor(reader: BinaryReader) : this(
        reader.readByte(),
        reader.readVarInt(),
        reader.readShort(),
        reader.readByte(),
        reader.readVarInt(),
        readChangedSlots(reader),
        reader.readItem()
    )

    override fun write(writer: BinaryWriter) {
        writer.writeByte(containerId)
        writer.writeVarInt(stateId)
        writer.writeShort(slot)
        writer.writeByte(button)
        writer.writeVarInt(mode)
        writer.writeVarInt(changedSlots.size)
        changedSlots.forEach { (changedSlot, item) ->
            writer.writeShort(changedSlot)
            writer.writeItem(item)
        }
        writer.writeItem(carriedItem)
    }

    override fun handle(handler: PlayPacketHandler) {
        handler.handleClickContainer(this)
    }

    companion object {

        @JvmStatic
        private fun readChangedSlots(reader: BinaryReader): Map<Short, KryptonItemStack> {
            val count = reader.readVarInt()
            val slots = HashMap<Short, KryptonItemStack>(count)
            for (i in 0 until count) {
                val changedSlot = reader.readShort()
                slots.put(changedSlot, reader.readItem())
            }
            return slots
        }
    }
}
