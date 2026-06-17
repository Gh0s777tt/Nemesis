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
import org.kryptonmc.krypton.packet.out.play.PacketOutRemoveEntityEffect
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the timed status effects a player has gained (e.g. from drinking a potion) and expires them, driven from the
 * shared ~1s tick (see `PlayerHandler.ensureContainerTick`). Each effect is given a lifetime in tick-cycles; every
 * cycle it counts down, and on reaching zero a Remove Entity Effect packet is sent so the client clears it too.
 * Re-applying an effect simply refreshes its remaining time. Disconnected players are pruned.
 */
object PlayerEffectManager {

    // player -> (effect id -> remaining shared-tick cycles)
    private val active = ConcurrentHashMap<KryptonPlayer, ConcurrentHashMap<Int, Int>>()

    fun add(player: KryptonPlayer, effectId: Int, cycles: Int) {
        active.computeIfAbsent(player) { ConcurrentHashMap() }[effectId] = maxOf(1, cycles)
    }

    fun tick() {
        val players = active.entries.iterator()
        while (players.hasNext()) {
            val (player, effects) = players.next()
            if (!player.isOnline()) { players.remove(); continue }
            val it = effects.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val remaining = entry.value - 1
                if (remaining <= 0) {
                    player.connection.send(PacketOutRemoveEntityEffect(player.id, entry.key))
                    it.remove()
                } else {
                    entry.setValue(remaining)
                }
            }
            if (effects.isEmpty()) players.remove()
        }
    }
}
