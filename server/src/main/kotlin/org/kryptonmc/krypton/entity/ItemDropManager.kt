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

import org.kryptonmc.api.item.ItemTypes
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.packet.out.play.PacketOutPickupItem
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks dropped-item entities ([KryptonItemEntity]) and lets a nearby player pick them up.
 *
 * Driven by the shared container/mob tick (see PlayPacketHandler.ensureContainerTick), so [tick]
 * runs roughly once per second. When a player is within [PICKUP_RADIUS_SQUARED] of an eligible drop,
 * the stack is added to their inventory, the pickup animation (PacketOutPickupItem) is broadcast, and
 * the drop entity is removed.
 */
object ItemDropManager {

    private const val PICKUP_RADIUS_SQUARED = 4.0
    private const val MERGE_RADIUS_SQUARED = 1.0 // two same-type drops within 1 block merge into one stack

    // Drops despawn after this many tick cycles (~1 s each) if never collected. Default 300 = 5 minutes,
    // matching vanilla; overridable via -Dkrypton.dropDespawnCycles=<n> (used by the despawn bot test).
    private val DESPAWN_CYCLES = System.getProperty("krypton.dropDespawnCycles")?.toIntOrNull() ?: 300

    // Protocol slots for the player's main inventory (9..35) and hotbar (36..44) in window 0.
    private val PLAYER_SLOTS = 9..44

    private val drops = CopyOnWriteArrayList<KryptonItemEntity>()

    fun add(drop: KryptonItemEntity) {
        drops.add(drop)
    }

    fun tick() {
        drops.removeIf { it.isRemoved() }
        mergeNearbyDrops()
        for (drop in drops) {
            if (drop.isRemoved()) continue
            if (++drop.age >= DESPAWN_CYCLES) {
                drop.remove() // never collected in time — despawn it (RemoveEntities to viewers)
                continue
            }
            if (drop.pickupDelay > 0) {
                drop.pickupDelay--
                continue
            }
            val collector = drop.world.players.firstOrNull {
                it.position.distanceSquared(drop.position) <= PICKUP_RADIUS_SQUARED
            } ?: continue

            giveToPlayer(collector, drop.item)
            val packet = PacketOutPickupItem(drop.id, collector.id, drop.item.amount)
            drop.sendPacketToViewers(packet)   // other players see the pickup animation
            collector.connection.send(packet)  // guarantee the collector sees it, even if not self-tracked
            drop.remove()
        }
    }

    /**
     * Merges same-type dropped stacks resting near each other into a single entity (like vanilla item merging).
     * The surviving drop grows (its metadata count re-broadcasts so clients re-render), the other is removed.
     */
    private fun mergeNearbyDrops() {
        for (i in drops.indices) {
            val a = drops[i]
            if (a.isRemoved() || isEmpty(a.item)) continue
            for (j in i + 1 until drops.size) {
                val b = drops[j]
                if (b.isRemoved() || isEmpty(b.item)) continue
                if (a.item.type != b.item.type) continue
                if (a.position.distanceSquared(b.position) > MERGE_RADIUS_SQUARED) continue
                val total = a.item.amount + b.item.amount
                if (total > a.item.type.maximumStackSize) continue // would overflow a stack — keep them separate
                a.item = a.item.withAmount(total) // grow the survivor (re-broadcasts its count)
                b.remove()                        // the other merged away (RemoveEntities to viewers)
            }
        }
    }

    /** Adds [item] to [player]'s inventory: stacks onto a matching slot, else fills the first empty one. */
    private fun giveToPlayer(player: KryptonPlayer, item: KryptonItemStack) {
        if (isEmpty(item)) return
        val inventory = player.inventory
        val max = item.type.maximumStackSize
        for (slot in PLAYER_SLOTS) {
            val current = inventory.getByProtocol(slot)
            if (!isEmpty(current) && current.type == item.type && current.amount < max) {
                inventory.setItem(slot, current.withAmount(minOf(max, current.amount + item.amount)))
                return
            }
        }
        for (slot in PLAYER_SLOTS) {
            if (isEmpty(inventory.getByProtocol(slot))) {
                inventory.setItem(slot, item)
                return
            }
        }
    }

    // KryptonItemStack.isEmpty() is unreliable (it compares the type to a registry reference that never
    // matches, and the EMPTY singleton has amount 1), so use a robust emptiness check here.
    private fun isEmpty(stack: KryptonItemStack): Boolean =
        stack === KryptonItemStack.EMPTY || stack.amount <= 0 || stack.type.key() == ItemTypes.AIR.key()
}
