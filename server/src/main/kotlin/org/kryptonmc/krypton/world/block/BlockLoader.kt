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
package org.kryptonmc.krypton.world.block

import com.google.gson.JsonObject
import net.kyori.adventure.key.Key
import org.kryptonmc.api.block.BlockSoundGroup
import org.kryptonmc.krypton.registry.KryptonRegistry
import org.kryptonmc.krypton.state.property.KryptonPropertyFactory
import org.kryptonmc.krypton.util.KryptonDataLoader
import org.kryptonmc.krypton.world.block.data.BlockSoundGroups
import org.kryptonmc.krypton.world.block.handlers.DefaultBlockHandler
import org.kryptonmc.krypton.world.material.Material
import org.kryptonmc.krypton.world.material.Materials
import java.lang.reflect.Modifier

class BlockLoader(registry: KryptonRegistry<KryptonBlock>) : KryptonDataLoader<KryptonBlock>("blocks", registry) {

    private val materialsByName: Map<String, Material> = Materials::class.java.declaredFields.asSequence()
        .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
        .filter { Material::class.java.isAssignableFrom(it.type) }
        .associate { it.name to it.get(null) as Material }

    private val soundGroupsByName: Map<String, BlockSoundGroup> = BlockSoundGroups::class.java.declaredFields.asSequence()
        .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
        .filter { BlockSoundGroup::class.java.isAssignableFrom(it.type) }
        .associate { it.name to it.get(null) as BlockSoundGroup }

    override fun create(key: Key, value: JsonObject): KryptonBlock {
        // ArticData's modern schema dropped the per-block `material`/`soundType` fields (Mojang removed the
        // Material class in 1.20), so we default those and derive the rest from the top-level + default-state data.
        val defaultMaterial = materialsByName.get("STONE") ?: materialsByName.values.first()
        val defaultSoundGroup = soundGroupsByName.get("STONE") ?: soundGroupsByName.values.first()

        val mojangName = value.get("mojangName").asString
        val isAir = mojangName == "AIR" || mojangName == "CAVE_AIR" || mojangName == "VOID_AIR"

        val defaultStateId = value.get("defaultStateId").asInt
        val states = value.getAsJsonArray("states")
        val defaultState = states.map { it.asJsonObject }.firstOrNull { it.get("stateId").asInt == defaultStateId }
            ?: states.first().asJsonObject

        val properties = BlockProperties(
            defaultMaterial,
            defaultState.get("blocksMotion")?.asBoolean ?: true,
            defaultSoundGroup,
            value.get("explosionResistance").asFloat,
            value.get("defaultHardness").asFloat,
            false, // requiresCorrectTool is not present in the modern ArticData schema
            value.get("friction").asFloat,
            value.get("speedFactor").asFloat,
            value.get("jumpFactor").asFloat,
            value.get("lootTableLocation")?.takeIf { !it.isJsonNull }?.let { Key.key(it.asString) },
            defaultState.get("occludes")?.asBoolean ?: true,
            isAir,
            value.get("dynamicShape").asBoolean
        )

        val stateProperties = value.get("properties").asJsonArray.map {
            KryptonPropertyFactory.findByName(PROPERTY_ALIASES.getOrDefault(it.asString, it.asString))
        }

        // TODO: Update this to get the handlers from somewhere
        return KryptonBlock(properties, DefaultBlockHandler, DefaultBlockHandler, DefaultBlockHandler, DefaultBlockHandler, stateProperties)
    }

    companion object {

        // The modern ArticData schema renamed several block-state properties; map them back to KryptonProperties' field names.
        private val PROPERTY_ALIASES: Map<String, String> = mapOf(
            "CHISELED_BOOKSHELF_SLOT_0_OCCUPIED" to "CHISELED_BOOKSHELF_FIRST_SLOT_OCCUPIED",
            "CHISELED_BOOKSHELF_SLOT_1_OCCUPIED" to "CHISELED_BOOKSHELF_SECOND_SLOT_OCCUPIED",
            "CHISELED_BOOKSHELF_SLOT_2_OCCUPIED" to "CHISELED_BOOKSHELF_THIRD_SLOT_OCCUPIED",
            "CHISELED_BOOKSHELF_SLOT_3_OCCUPIED" to "CHISELED_BOOKSHELF_FOURTH_SLOT_OCCUPIED",
            "CHISELED_BOOKSHELF_SLOT_4_OCCUPIED" to "CHISELED_BOOKSHELF_FIFTH_SLOT_OCCUPIED",
            "CHISELED_BOOKSHELF_SLOT_5_OCCUPIED" to "CHISELED_BOOKSHELF_SIXTH_SLOT_OCCUPIED",
            "EAST_REDSTONE" to "EAST_REDSTONE_SIDE",
            "NORTH_REDSTONE" to "NORTH_REDSTONE_SIDE",
            "SOUTH_REDSTONE" to "SOUTH_REDSTONE_SIDE",
            "WEST_REDSTONE" to "WEST_REDSTONE_SIDE",
            "EAST_WALL" to "EAST_WALL_SIDE",
            "NORTH_WALL" to "NORTH_WALL_SIDE",
            "SOUTH_WALL" to "SOUTH_WALL_SIDE",
            "WEST_WALL" to "WEST_WALL_SIDE",
            "FACING_HOPPER" to "HOPPER_FACING",
            "HAS_BOTTLE_0" to "HAS_FIRST_BOTTLE",
            "HAS_BOTTLE_1" to "HAS_SECOND_BOTTLE",
            "HAS_BOTTLE_2" to "HAS_THIRD_BOTTLE",
            "LEVEL_CAULDRON" to "CAULDRON_LEVEL",
            "LEVEL_COMPOSTER" to "COMPOSTER_LEVEL",
            "LEVEL_HONEY" to "HONEY_LEVEL",
            "MODE_COMPARATOR" to "COMPARATOR_MODE",
            "STRUCTUREBLOCK_MODE" to "STRUCTURE_MODE",
            "NOTEBLOCK_INSTRUMENT" to "INSTRUMENT",
            "RAIL_SHAPE_STRAIGHT" to "STRAIGHT_RAIL_SHAPE",
            "ROTATION_16" to "ROTATION",
            "STABILITY_DISTANCE" to "SCAFFOLD_DISTANCE",
            "STAIRS_SHAPE" to "STAIR_SHAPE"
        )
    }
}
