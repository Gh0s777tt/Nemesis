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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks spawned mobs. The earlier placeholder oscillation (mob increment 2) is now SUPERSEDED by real
 * AI: each mob pursues the nearest player via its own per-tick [KryptonMob.tick] →
 * [org.kryptonmc.krypton.entity.ai.pathfinding.KryptonNavigator] (see KryptonMob.aiTick). This object now
 * only keeps a lightweight registry and prunes despawned mobs; movement is entity-driven, not done here.
 */
object MobMover {

    private val mobs = CopyOnWriteArrayList<KryptonEntity>()
    private val LOGGER = LogManager.getLogger()

    // Per-cycle baby aging (the tick runs ~1 s). Default grows over real time; override high for fast tests:
    // -Dkrypton.babyGrowthPerCycle=<n>. The age setter flips the BABY metadata (client renders adult) at age 0.
    private val BABY_GROWTH = System.getProperty("krypton.babyGrowthPerCycle")?.toIntOrNull() ?: 1

    fun add(entity: KryptonEntity) {
        mobs.add(entity)
    }

    fun tick() {
        mobs.removeIf { it.isRemoved() } // drop despawned / killed mobs
        for (mob in mobs) {
            if (mob is KryptonAgeable && mob.isBaby) {
                mob.increaseAge(BABY_GROWTH)
                if (!mob.isBaby) LOGGER.info("Baby entity ${mob.id} grew into an adult")
            }
        }
    }
}
