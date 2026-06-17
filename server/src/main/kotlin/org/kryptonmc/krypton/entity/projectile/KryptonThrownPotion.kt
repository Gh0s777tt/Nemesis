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
package org.kryptonmc.krypton.entity.projectile

import org.kryptonmc.api.entity.projectile.ThrownPotion
import org.kryptonmc.api.item.ItemTypes
import org.kryptonmc.api.util.Vec3d
import org.kryptonmc.krypton.entity.KryptonEntityType
import org.kryptonmc.krypton.entity.KryptonEntityTypes
import org.kryptonmc.krypton.entity.PlayerEffectManager
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.packet.out.play.PacketOutEntityEffect
import org.kryptonmc.krypton.world.KryptonWorld
import kotlin.math.floor

/**
 * A thrown splash potion. It flies ballistically (horizontal launch velocity decaying with drag, plus gravity via
 * [fallToGround]) for a short time, then bursts: every player within [SPLASH_RADIUS] gets [effectId] applied (tracked
 * by [PlayerEffectManager] so it expires), and the entity is removed. Simplified: it bursts on a flight timer rather
 * than on precise block/entity collision, and the effect duration/amplifier are fixed.
 */
class KryptonThrownPotion(world: KryptonWorld) : KryptonThrowableProjectile(world), ThrownPotion {

    override val type: KryptonEntityType<KryptonThrownPotion>
        get() = KryptonEntityTypes.POTION

    /** The mob-effect id this potion applies to nearby players when it bursts (set by the thrower). */
    var effectId: Int = DEFAULT_EFFECT
    private var flightTicks = 0

    override fun tick(time: Long) {
        super.tick(time)
        if (isRemoved()) return
        val v = velocity
        if (v.x * v.x + v.z * v.z > VELOCITY_EPSILON) {
            teleport(position.withCoordinates(position.x + v.x, position.y, position.z + v.z)) // also re-broadcasts position
            velocity = Vec3d(v.x * HORIZONTAL_DRAG, 0.0, v.z * HORIZONTAL_DRAG)
        }
        fallToGround() // gravity
        // Burst on hitting a solid block (the cell it's in, or the one just below as it lands) — or, failing that,
        // after the flight timeout.
        val px = floor(position.x).toInt()
        val py = floor(position.y).toInt()
        val pz = floor(position.z).toInt()
        val hitBlock = world.getBlock(px, py, pz).isSolid() || world.getBlock(px, py - 1, pz).isSolid()
        if (hitBlock || ++flightTicks >= IMPACT_TICKS) {
            splash()
            remove()
        }
    }

    private fun splash() {
        val cycles = (EFFECT_DURATION_TICKS + SHARED_TICK_TICKS - 1) / SHARED_TICK_TICKS
        for (target in world.players) {
            val dx = target.position.x - position.x
            val dy = target.position.y - position.y
            val dz = target.position.z - position.z
            if (dx * dx + dy * dy + dz * dz <= SPLASH_RADIUS * SPLASH_RADIUS) {
                target.connection.send(PacketOutEntityEffect(target.id, effectId, 0, EFFECT_DURATION_TICKS, EFFECT_FLAGS))
                PlayerEffectManager.add(target, effectId, cycles)
            }
        }
    }

    override fun defaultItem(): KryptonItemStack = DEFAULT_ITEM

    companion object {

        private val DEFAULT_ITEM = KryptonItemStack(ItemTypes.SPLASH_POTION.get())
        private const val DEFAULT_EFFECT = 1          // Speed, if the thrower didn't set one
        private val IMPACT_TICKS = System.getProperty("krypton.potionFlightTicks")?.toIntOrNull() ?: 10 // flight timeout (test hook)
        private const val HORIZONTAL_DRAG = 0.96      // light air friction (less than a dropped item, so it flies)
        private const val VELOCITY_EPSILON = 1.0E-3
        private const val SPLASH_RADIUS = 10.0        // generous, to cover the arc's drop on impact
        private const val EFFECT_DURATION_TICKS = 600
        private const val EFFECT_FLAGS = 0x06         // show particles + icon
        private const val SHARED_TICK_TICKS = 20
    }
}
