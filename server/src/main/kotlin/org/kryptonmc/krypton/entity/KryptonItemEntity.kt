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

import org.kryptonmc.api.util.Vec3d
import org.kryptonmc.krypton.entity.metadata.MetadataKeys
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.world.KryptonWorld

/**
 * A dropped-item entity ("item") that lies on the ground until a nearby player collects it.
 *
 * The carried stack is exposed to clients through entity metadata index 8 (ITEM_STACK), which the
 * client uses to render the floating item model. Pickup is driven by [ItemDropManager].
 */
class KryptonItemEntity(world: KryptonWorld) : KryptonEntity(world) {

    override val type: KryptonEntityType<KryptonEntity>
        get() = KryptonEntityTypes.ITEM

    /** Pickup-manager iterations remaining before this drop becomes collectable (0 = immediately). */
    var pickupDelay: Int = 0

    /** Pickup-manager iterations this drop has existed; used to despawn it after a while. */
    var age: Int = 0

    /** The stack carried by this drop, backed by entity metadata so the client renders it. */
    var item: KryptonItemStack
        get() = data.get(MetadataKeys.Item.ITEM)
        set(value) = data.set(MetadataKeys.Item.ITEM, value)

    override fun defineData() {
        super.defineData()
        data.define(MetadataKeys.Item.ITEM, KryptonItemStack.EMPTY)
    }

    override fun tick(time: Long) {
        super.tick(time)
        if (isRemoved()) return
        // Horizontal drift from any launch velocity (toss/dispense), decaying with friction each tick; the
        // vertical fall is handled by fallToGround. Setting `velocity` also re-broadcasts it to viewers.
        val v = velocity
        if (v.x * v.x + v.z * v.z > VELOCITY_EPSILON) {
            teleport(position.withCoordinates(position.x + v.x, position.y, position.z + v.z))
            val nx = v.x * HORIZONTAL_DRAG
            val nz = v.z * HORIZONTAL_DRAG
            velocity = if (nx * nx + nz * nz > VELOCITY_EPSILON) Vec3d(nx, 0.0, nz) else Vec3d.ZERO
        }
        fallToGround() // dropped/tossed items fall to the ground
    }

    companion object {

        private const val HORIZONTAL_DRAG = 0.8       // per-tick decay of horizontal launch velocity (ground/air friction)
        private const val VELOCITY_EPSILON = 1.0E-3   // below this the drift is negligible — stop moving/broadcasting
    }
}
