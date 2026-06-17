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
package org.kryptonmc.krypton.world.chunk

import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry
import net.kyori.adventure.key.InvalidKeyException
import net.kyori.adventure.key.Key
import org.apache.logging.log4j.LogManager
import org.kryptonmc.api.resource.ResourceKeys
import org.kryptonmc.api.util.Vec3i
import org.kryptonmc.api.world.biome.Biome
import org.kryptonmc.krypton.KryptonPlatform
import org.kryptonmc.krypton.coordinate.ChunkPos
import org.kryptonmc.krypton.entity.EntityFactory
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.inventory.KryptonChestInventory
import org.kryptonmc.krypton.registry.KryptonRegistries
import org.kryptonmc.krypton.registry.KryptonRegistry
import org.kryptonmc.krypton.util.DataConversion
import org.kryptonmc.krypton.util.nbt.getDataVersion
import org.kryptonmc.krypton.util.nbt.putDataVersion
import org.kryptonmc.krypton.world.KryptonWorld
import org.kryptonmc.krypton.world.biome.BiomeKeys
import org.kryptonmc.krypton.world.block.KryptonBlocks
import org.kryptonmc.krypton.world.block.palette.PaletteHolder
import org.kryptonmc.krypton.world.block.state.KryptonBlockState
import org.kryptonmc.krypton.world.chunk.data.ChunkSection
import org.kryptonmc.krypton.world.chunk.data.Heightmap
import org.kryptonmc.krypton.world.region.RegionFileManager
import org.kryptonmc.nbt.ByteArrayTag
import org.kryptonmc.nbt.CompoundTag
import org.kryptonmc.nbt.ImmutableCompoundTag
import org.kryptonmc.nbt.ImmutableListTag
import org.kryptonmc.nbt.ListTag
import org.kryptonmc.nbt.LongArrayTag
import org.kryptonmc.nbt.StringTag
import org.kryptonmc.nbt.buildCompound
import org.kryptonmc.nbt.compound
import org.kryptonmc.serialization.nbt.NbtOps
import java.nio.file.Path
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class VanillaChunkLoader(worldFolder: Path) : ChunkLoader {

    private val regionManager = RegionFileManager(worldFolder.resolve("region"))
    private val entityRegionManager = RegionFileManager(worldFolder.resolve("entities"))

    override fun loadChunk(world: KryptonWorld, pos: ChunkPos): KryptonChunk? {
        val nbt = regionManager.read(pos.x, pos.z) ?: return generateChunk(world, pos)
        return loadData(world, pos, nbt)
    }

    /**
     * Generates a fresh chunk when no saved region data exists, so unexplored area is solid ground instead of void.
     * Simple flat terrain: bedrock floor, a few stone layers, dirt, and grass on top at [GEN_SURFACE_Y].
     */
    private fun generateChunk(world: KryptonWorld, pos: ChunkPos): KryptonChunk {
        val chunk = KryptonChunk(world, pos, fillMissingSections(world, arrayOfNulls(world.sectionCount())))
        // Initialise the heightmaps up front: KryptonChunk.setBlock updates these four, so their map entries must
        // already exist before we place any block (otherwise getValue throws "MOTION_BLOCKING is missing").
        Heightmap.prime(chunk, EnumSet.of(
            Heightmap.Type.MOTION_BLOCKING,
            Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Type.OCEAN_FLOOR,
            Heightmap.Type.WORLD_SURFACE
        ))
        val baseX = pos.x shl 4
        val baseZ = pos.z shl 4
        for (lx in 0..15) {
            for (lz in 0..15) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val surface = surfaceHeight(wx, wz) // rolling hills: per-column height from deterministic noise
                val biome = biomeAt(wx, wz)         // biome zone: plains / desert / forest / snowy
                val underwater = surface < SEA_LEVEL // a dip below sea level — becomes a lake bed
                val subBlock = if (biome == BIOME_DESERT) KryptonBlocks.SAND.defaultState else KryptonBlocks.DIRT.defaultState
                // Surface: sand in desert; a dirt lake bed under water (grass would be submerged); else grass.
                val topBlock = when {
                    biome == BIOME_DESERT -> KryptonBlocks.SAND.defaultState
                    underwater -> KryptonBlocks.DIRT.defaultState
                    else -> KryptonBlocks.GRASS_BLOCK.defaultState
                }
                chunk.setBlock(Vec3i(wx, GEN_FLOOR_Y, wz), KryptonBlocks.BEDROCK.defaultState, false)
                // Stone body, with coal/iron ore scattered through it; 3D-noise cave pockets are left as air.
                for (y in GEN_FLOOR_Y + 1 until surface - 2) {
                    if (isCave(wx, y, wz)) continue // carve a cave: leave this block as air
                    val h = oreHash(wx, y, wz)
                    val block = when {
                        h % 73 == 0 -> KryptonBlocks.COAL_ORE.defaultState
                        h % 113 == 0 -> KryptonBlocks.IRON_ORE.defaultState
                        else -> KryptonBlocks.STONE.defaultState
                    }
                    chunk.setBlock(Vec3i(wx, y, wz), block, false)
                }
                chunk.setBlock(Vec3i(wx, surface - 2, wz), subBlock, false)
                chunk.setBlock(Vec3i(wx, surface - 1, wz), subBlock, false)
                chunk.setBlock(Vec3i(wx, surface, wz), topBlock, false)
                // Flood any dip below sea level (lakes/ponds); else blanket the snowy biome surface with a snow layer.
                if (underwater) {
                    for (y in surface + 1..SEA_LEVEL) chunk.setBlock(Vec3i(wx, y, wz), KryptonBlocks.WATER.defaultState, false)
                } else if (biome == BIOME_SNOWY) {
                    chunk.setBlock(Vec3i(wx, surface + 1, wz), KryptonBlocks.SNOW.defaultState, false)
                }
            }
        }
        // Natural tree scatter (local 2..13 so the canopy stays in-chunk). Density + species follow the chunk biome:
        // forest = dense oak, plains = sparse oak, snowy = a few spruce, desert = none. Never under water.
        val maxTrees = when (biomeAt(baseX + 8, baseZ + 8)) {
            BIOME_DESERT -> 0
            BIOME_FOREST -> FOREST_MAX_TREES
            BIOME_SNOWY -> SNOWY_MAX_TREES
            else -> MAX_TREES_PER_CHUNK
        }
        val rng = java.util.Random(pos.x * 341873128712L + pos.z * 132897987541L)
        repeat(rng.nextInt(maxTrees + 1)) {
            val tx = baseX + 2 + rng.nextInt(12)
            val tz = baseZ + 2 + rng.nextInt(12)
            val s = surfaceHeight(tx, tz)
            val b = biomeAt(tx, tz)
            if (b != BIOME_DESERT && s >= SEA_LEVEL) generateTree(chunk, tx, tz, s, b == BIOME_SNOWY)
        }
        // Structures: a clustered village (centre well + a ring of houses in the 8 neighbouring chunks) takes
        // precedence; otherwise a rare lone building. Per chunk these are mutually exclusive so footprints never overlap.
        val villageRole = villageRoleFor(pos.x, pos.z)
        when (villageRole) {
            VILLAGE_WELL -> maybeGenerateWell(chunk, baseX + WELL_ANCHOR, baseZ + WELL_ANCHOR)    // village centre
            VILLAGE_HOUSE -> maybeGenerateHouse(chunk, baseX + HOUSE_ANCHOR, baseZ + HOUSE_ANCHOR) // village ring
            else -> {
                if (chunkHasHouse(pos.x, pos.z)) maybeGenerateHouse(chunk, baseX + HOUSE_ANCHOR, baseZ + HOUSE_ANCHOR)
                else if (chunkHasWell(pos.x, pos.z)) maybeGenerateWell(chunk, baseX + WELL_ANCHOR, baseZ + WELL_ANCHOR)
                else if (chunkHasTower(pos.x, pos.z)) maybeGenerateTower(chunk, baseX + HOUSE_ANCHOR, baseZ + HOUSE_ANCHOR)
                else if (chunkHasDesertWell(pos.x, pos.z)) maybeGenerateDesertWell(chunk, baseX + WELL_ANCHOR, baseZ + WELL_ANCHOR)
                else if (chunkHasRuins(pos.x, pos.z)) maybeGenerateRuins(chunk, baseX + HOUSE_ANCHOR, baseZ + HOUSE_ANCHOR)
            }
        }
        if (villageRole != VILLAGE_NONE) { drawVillagePaths(chunk, baseX, baseZ); generateFarm(chunk) } // avenues + a farm plot
        return chunk
    }

    /** Low-frequency zone noise: true => desert biome (sand surface, no trees), false => plains (grass + trees). */
    private fun biomeIsDesert(x: Int, z: Int): Boolean {
        val n = sin(x * 0.025) + cos(z * 0.025) + 0.5 * sin((x - z) * 0.018)
        return n > BIOME_DESERT_THRESHOLD
    }

    /**
     * Biome at world ([x],[z]): desert keeps its existing zone exactly (no regression); a second low-frequency noise
     * field then carves snowy / forest out of the remaining area, leaving plains as the default.
     */
    private fun biomeAt(x: Int, z: Int): Int {
        if (biomeIsDesert(x, z)) return BIOME_DESERT
        val n = sin(x * 0.013) + cos(z * 0.014)
        return when {
            n > BIOME_SUBZONE_THRESHOLD -> BIOME_SNOWY
            n < -BIOME_SUBZONE_THRESHOLD -> BIOME_FOREST
            else -> BIOME_PLAINS
        }
    }

    /** Plants a tree at column ([tx], [tz]) standing on [surface]: a log trunk topped with a leaves canopy (spruce if [spruce], else oak). */
    private fun generateTree(chunk: KryptonChunk, tx: Int, tz: Int, surface: Int, spruce: Boolean) {
        val log = (if (spruce) KryptonBlocks.SPRUCE_LOG else KryptonBlocks.OAK_LOG).defaultState
        val leaves = (if (spruce) KryptonBlocks.SPRUCE_LEAVES else KryptonBlocks.OAK_LEAVES).defaultState
        val trunkTop = surface + TREE_TRUNK_HEIGHT
        for (y in surface + 1..trunkTop) chunk.setBlock(Vec3i(tx, y, tz), log, false)
        // Canopy: 5x5 around the top two trunk levels, a 3x3 above, then a single leaf cap.
        for (y in trunkTop - 1..trunkTop) {
            for (dx in -2..2) for (dz in -2..2) {
                if (dx == 0 && dz == 0) continue // keep the trunk log at this column
                setLeafIfAir(chunk, tx + dx, y, tz + dz, leaves)
            }
        }
        for (dx in -1..1) for (dz in -1..1) setLeafIfAir(chunk, tx + dx, trunkTop + 1, tz + dz, leaves)
        setLeafIfAir(chunk, tx, trunkTop + 2, tz, leaves)
    }

    private fun setLeafIfAir(chunk: KryptonChunk, x: Int, y: Int, z: Int, leaves: KryptonBlockState) {
        if (chunk.getBlock(x, y, z).isAir()) chunk.setBlock(Vec3i(x, y, z), leaves, false)
    }

    /** Plants a lamp post (oak-fence column topped with a lantern) at this chunk's lighting spot — village lighting. */
    private fun generateLampPost(chunk: KryptonChunk) {
        val wx = (chunk.x shl 4) + LAMP_LOCAL_X
        val wz = (chunk.z shl 4) + LAMP_LOCAL_Z
        val surface = surfaceHeight(wx, wz)
        if (surface < SEA_LEVEL) return // don't stand a lamp post in a lake
        val fence = KryptonBlocks.OAK_FENCE.defaultState
        val lantern = KryptonBlocks.LANTERN.defaultState
        val air = KryptonBlocks.AIR.defaultState
        for (y in surface + 1..surface + LAMP_POST_HEIGHT + 2) chunk.setBlock(Vec3i(wx, y, wz), air, false) // clear any tree
        for (y in surface + 1..surface + LAMP_POST_HEIGHT) chunk.setBlock(Vec3i(wx, y, wz), fence, false)
        chunk.setBlock(Vec3i(wx, surface + LAMP_POST_HEIGHT + 1, wz), lantern, false)
    }

    /** Deterministic per-chunk structure roll: true for ~1 in [HOUSE_CHANCE_INV] chunks (a candidate village-house site). */
    private fun chunkHasHouse(cx: Int, cz: Int): Boolean {
        var h = cx * 73856093 xor (cz * 19349663)
        h = h xor (h ushr 13)
        return (h and 0x7FFFFFFF) % HOUSE_CHANCE_INV == 0
    }

    /** Builds the village house at the chunk-local anchor only if the ground is plains, on land and flat enough. */
    private fun maybeGenerateHouse(chunk: KryptonChunk, x: Int, z: Int) {
        val bio = biomeAt(x + 2, z + 2); if (bio == BIOME_DESERT || bio == BIOME_SNOWY) return // houses only on plains/forest
        val a = surfaceHeight(x, z); val b = surfaceHeight(x + 4, z); val c = surfaceHeight(x, z + 4)
        val d = surfaceHeight(x + 4, z + 4); val e = surfaceHeight(x + 2, z + 2)
        val maxS = maxOf(a, b, c, d, e)
        val minS = minOf(a, b, c, d, e)
        if (minS < SEA_LEVEL) return            // don't build over / sunk in a lake
        if (maxS - minS > HOUSE_MAX_SLOPE) return // too steep — would bury or float badly
        generateHouse(chunk, x, z, maxS)        // floor at the highest corner; a foundation fills the lower ones
    }

    /**
     * Builds a small village house: a 5x5 footprint with near corner at world ([x],[z]), oak-plank floor at [floorY],
     * cobblestone walls with oak-log corner posts [HOUSE_WALL_HEIGHT] tall, glass windows, a 2-high door opening, and a
     * flat oak-plank roof on top. A cobblestone foundation fills any gap down to each column's surface so it never floats.
     */
    private fun generateHouse(chunk: KryptonChunk, x: Int, z: Int, floorY: Int) {
        val planks = KryptonBlocks.OAK_PLANKS.defaultState
        val cobble = KryptonBlocks.COBBLESTONE.defaultState
        val post = KryptonBlocks.OAK_LOG.defaultState
        val glass = KryptonBlocks.GLASS.defaultState
        val air = KryptonBlocks.AIR.defaultState
        val wallHeight = HOUSE_WALL_HEIGHT + oreHash(x, 0, z) % 2 // vary 3 or 4 high so houses aren't identical clones
        val roofY = floorY + wallHeight + 1
        for (dx in 0..4) for (dz in 0..4) {
            val wx = x + dx
            val wz = z + dz
            for (y in surfaceHeight(wx, wz) + 1 until floorY) chunk.setBlock(Vec3i(wx, y, wz), cobble, false) // foundation
            for (y in floorY + 1..roofY + 2) chunk.setBlock(Vec3i(wx, y, wz), air, false) // clear interior + any tree above
            chunk.setBlock(Vec3i(wx, floorY, wz), planks, false) // floor
            val edge = dx == 0 || dx == 4 || dz == 0 || dz == 4
            val corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4)
            if (edge) for (y in floorY + 1..floorY + wallHeight) {
                chunk.setBlock(Vec3i(wx, y, wz), if (corner) post else cobble, false) // log corner posts, cobble walls
            }
            chunk.setBlock(Vec3i(wx, roofY, wz), planks, false) // flat roof
        }
        // A 2-high door opening in the middle of the +z wall, and a glass window centred on each of the other three walls.
        chunk.setBlock(Vec3i(x + 2, floorY + 1, z + 4), air, false)
        chunk.setBlock(Vec3i(x + 2, floorY + 2, z + 4), air, false)
        chunk.setBlock(Vec3i(x + 2, floorY + 2, z), glass, false)
        chunk.setBlock(Vec3i(x, floorY + 2, z + 2), glass, false)
        chunk.setBlock(Vec3i(x + 4, floorY + 2, z + 2), glass, false)
        generateLampPost(chunk) // a lit lamp post in this chunk's lighting spot
    }

    /** Deterministic per-chunk well roll, independent of [chunkHasHouse] (different mixing constants). */
    private fun chunkHasWell(cx: Int, cz: Int): Boolean {
        var h = cx * 19349663 xor (cz * 83492791)
        h = h xor (h ushr 13)
        return (h and 0x7FFFFFFF) % WELL_CHANCE_INV == 0
    }

    /** Deterministic per-chunk lone-watchtower roll (built only on plains/forest), independent of the other structures. */
    private fun chunkHasTower(cx: Int, cz: Int): Boolean {
        var h = cx * 374761393 xor (cz * 668265263)
        h = h xor (h ushr 15)
        return (h and 0x7FFFFFFF) % TOWER_CHANCE_INV == 0
    }

    /** Deterministic per-chunk lone desert-well roll (built only in desert), independent of the other structures. */
    private fun chunkHasDesertWell(cx: Int, cz: Int): Boolean {
        var h = cx * 83492791 xor (cz * 19349663)
        h = h xor (h ushr 14)
        return (h and 0x7FFFFFFF) % DESERT_WELL_CHANCE_INV == 0
    }

    /** Deterministic per-chunk lone-ruins roll (built only on plains/forest), independent of the other structures. */
    private fun chunkHasRuins(cx: Int, cz: Int): Boolean {
        var h = cx * 22695477 xor (cz * 1103515245)
        h = h xor (h ushr 13)
        return (h and 0x7FFFFFFF) % RUINS_CHANCE_INV == 0
    }

    /** True if this chunk anchors a village — a rare roll, gated so the centre well's footprint actually builds. */
    private fun isVillageCenter(cx: Int, cz: Int): Boolean {
        var h = cx * 668265263 xor (cz * 374761393)
        h = h xor (h ushr 15)
        if ((h and 0x7FFFFFFF) % VILLAGE_CHANCE_INV != 0) return false
        return wellFloorY((cx shl 4) + WELL_ANCHOR, (cz shl 4) + WELL_ANCHOR) >= 0 // only anchor where the centre well builds
    }

    /**
     * A chunk's role in a village: [VILLAGE_WELL] if it's a centre, [VILLAGE_HOUSE] if it neighbours one (the 8
     * surrounding chunks form the house ring), else [VILLAGE_NONE]. Self-centre wins so two adjacent centres each
     * keep their own well.
     */
    private fun villageRoleFor(cx: Int, cz: Int): Int {
        if (isVillageCenter(cx, cz)) return VILLAGE_WELL // this chunk is a village centre
        for (dx in -1..1) for (dz in -1..1) {
            if (dx == 0 && dz == 0) continue
            if (isVillageCenter(cx + dx, cz + dz)) return VILLAGE_HOUSE
        }
        return VILLAGE_NONE
    }

    /**
     * Paves cardinal "avenue" paths (dirt_path on the surface) along the two world lines through the village's well
     * centre (x = well-centre-x, z = well-centre-z), each reaching [AVENUE_LEN] blocks toward the cardinal houses.
     * Each chunk paves only the portion of those lines inside it, so segments meet at chunk borders (no cross-chunk
     * writes). The central 5..9 box (well/house footprint) is left unpaved.
     */
    private fun drawVillagePaths(chunk: KryptonChunk, baseX: Int, baseZ: Int) {
        val cx = chunk.x
        val cz = chunk.z
        var ccx = 0; var ccz = 0; var found = false
        for (dx in -1..1) for (dz in -1..1) if (isVillageCenter(cx + dx, cz + dz)) { ccx = cx + dx; ccz = cz + dz; found = true }
        if (!found) return
        val xc = (ccx shl 4) + WELL_ANCHOR + 1 // well-centre world x (vertical avenue line)
        val zc = (ccz shl 4) + WELL_ANCHOR + 1 // well-centre world z (horizontal avenue line)
        val path = KryptonBlocks.DIRT_PATH.defaultState
        for (lx in 0..15) for (lz in 0..15) {
            if (lx in 5..9 && lz in 5..9) continue // leave the structure footprint unpaved
            val wx = baseX + lx
            val wz = baseZ + lz
            val ax = abs(wx - xc)
            val az = abs(wz - zc)
            // cardinal avenues (along x=xc / z=zc) plus diagonal avenues (|dx|==|dz|) reaching the diagonal houses
            val onAvenue = (wz == zc && ax <= AVENUE_LEN) || (wx == xc && az <= AVENUE_LEN) || (ax == az && ax in 1..AVENUE_LEN)
            if (!onAvenue) continue
            val s = surfaceHeight(wx, wz)
            if (s < SEA_LEVEL) continue // don't pave across water
            chunk.setBlock(Vec3i(wx, s, wz), path, false)
        }
    }

    /** Plants a small 5x5 wheat farm (tilled farmland + a central water source + crops) in a cardinal ring chunk's free corner. */
    private fun generateFarm(chunk: KryptonChunk) {
        val cx = chunk.x
        val cz = chunk.z
        var ccx = 0; var ccz = 0; var found = false
        for (dx in -1..1) for (dz in -1..1) if (isVillageCenter(cx + dx, cz + dz)) { ccx = cx + dx; ccz = cz + dz; found = true }
        if (!found || abs(cx - ccx) + abs(cz - ccz) != 1) return // farms only beside a centre (cardinal ring chunk)
        val baseX = cx shl 4
        val baseZ = cz shl 4
        val farmland = KryptonBlocks.FARMLAND.defaultState
        val water = KryptonBlocks.WATER.defaultState
        val wheat = KryptonBlocks.WHEAT.defaultState
        val air = KryptonBlocks.AIR.defaultState
        for (lx in 0..4) for (lz in 10..14) {
            val wx = baseX + lx
            val wz = baseZ + lz
            val s = surfaceHeight(wx, wz)
            if (s < SEA_LEVEL) continue
            for (y in s + 1..s + 3) chunk.setBlock(Vec3i(wx, y, wz), air, false) // clear grass/tree above the plot
            if (lx == 2 && lz == 12) {
                chunk.setBlock(Vec3i(wx, s, wz), water, false) // central water hydrates the field
            } else {
                chunk.setBlock(Vec3i(wx, s, wz), farmland, false)
                chunk.setBlock(Vec3i(wx, s + 1, wz), wheat, false)
            }
        }
    }

    /** The well's floor Y if its 4x4 footprint at world ([x],[z]) is buildable (plains, on land, flat enough), else -1. */
    private fun wellFloorY(x: Int, z: Int): Int {
        val bio = biomeAt(x + 1, z + 1); if (bio == BIOME_DESERT || bio == BIOME_SNOWY) return -1 // wells only on plains/forest
        val a = surfaceHeight(x, z); val b = surfaceHeight(x + 3, z); val c = surfaceHeight(x, z + 3)
        val d = surfaceHeight(x + 3, z + 3); val e = surfaceHeight(x + 1, z + 1)
        val maxS = maxOf(a, b, c, d, e)
        val minS = minOf(a, b, c, d, e)
        if (minS < SEA_LEVEL || maxS - minS > HOUSE_MAX_SLOPE) return -1
        return maxS
    }

    /** Builds the village/lone cobblestone well at the chunk-local anchor only if its footprint is buildable (plains/forest). */
    private fun maybeGenerateWell(chunk: KryptonChunk, x: Int, z: Int) {
        val floorY = wellFloorY(x, z)
        if (floorY >= 0) generateWell(chunk, x, z, floorY, KryptonBlocks.COBBLESTONE.defaultState, true)
    }

    /** Builds a lone sandstone desert well — fills the otherwise empty desert biome. Flat sand, on land, no lamp. */
    private fun maybeGenerateDesertWell(chunk: KryptonChunk, x: Int, z: Int) {
        if (biomeAt(x + 1, z + 1) != BIOME_DESERT) return // desert wells only in desert
        val a = surfaceHeight(x, z); val b = surfaceHeight(x + 3, z); val c = surfaceHeight(x, z + 3)
        val d = surfaceHeight(x + 3, z + 3); val e = surfaceHeight(x + 1, z + 1)
        val maxS = maxOf(a, b, c, d, e); val minS = minOf(a, b, c, d, e)
        if (minS < SEA_LEVEL || maxS - minS > HOUSE_MAX_SLOPE) return
        generateWell(chunk, x, z, maxS, KryptonBlocks.SANDSTONE.defaultState, false)
    }

    /**
     * Builds a village well: a 4x4 footprint with near corner at world ([x],[z]) and rim at [floorY] — a cobblestone
     * rim around a 2x2 water pool, four cobblestone corner posts [WELL_HEIGHT] tall holding up a flat cobblestone roof,
     * with open sides between the posts. A cobblestone slab under the pool stops the water draining into a cave.
     */
    private fun generateWell(chunk: KryptonChunk, x: Int, z: Int, floorY: Int, wall: KryptonBlockState, lamp: Boolean) {
        val water = KryptonBlocks.WATER.defaultState
        val air = KryptonBlocks.AIR.defaultState
        val roofY = floorY + WELL_HEIGHT + 1
        for (dx in 0..3) for (dz in 0..3) {
            val wx = x + dx
            val wz = z + dz
            for (y in floorY + 1..roofY + 2) chunk.setBlock(Vec3i(wx, y, wz), air, false) // clear interior + any tree above
            val inner = dx in 1..2 && dz in 1..2
            if (inner) chunk.setBlock(Vec3i(wx, floorY, wz), water, false) // 2x2 water pool
            else chunk.setBlock(Vec3i(wx, floorY, wz), wall, false)                          // cobblestone/sandstone rim
            if ((dx == 0 || dx == 3) && (dz == 0 || dz == 3)) {
                for (y in floorY + 1..floorY + WELL_HEIGHT) chunk.setBlock(Vec3i(wx, y, wz), wall, false) // corner posts
            }
            chunk.setBlock(Vec3i(wx, roofY, wz), wall, false) // flat roof
        }
        // Seal the pool floor so the water can't drain into a carved cave directly below.
        for (dx in 1..2) for (dz in 1..2) {
            if (chunk.getBlock(x + dx, floorY - 1, z + dz).isAir()) chunk.setBlock(Vec3i(x + dx, floorY - 1, z + dz), wall, false)
        }
        if (lamp) generateLampPost(chunk) // village wells get a lit lamp post; lone desert wells don't
    }

    /** Builds the lone watchtower at the chunk-local anchor only if the ground is plains/forest and flat enough. */
    private fun maybeGenerateTower(chunk: KryptonChunk, x: Int, z: Int) {
        val bio = biomeAt(x + 2, z + 2); if (bio == BIOME_DESERT || bio == BIOME_SNOWY) return // towers on plains/forest
        val a = surfaceHeight(x, z); val b = surfaceHeight(x + 4, z); val c = surfaceHeight(x, z + 4)
        val d = surfaceHeight(x + 4, z + 4); val e = surfaceHeight(x + 2, z + 2)
        val maxS = maxOf(a, b, c, d, e); val minS = minOf(a, b, c, d, e)
        if (minS < SEA_LEVEL || maxS - minS > HOUSE_MAX_SLOPE) return
        generateTower(chunk, x, z, maxS)
    }

    /**
     * Builds a tall watchtower: a 5x5 footprint with near corner at world ([x],[z]), floor (cobblestone) at [floorY],
     * cobblestone walls [TOWER_HEIGHT] tall around a hollow shaft, a flat roof platform on top with cobblestone-wall
     * battlements around its edge, and a door opening at the base. A foundation fills any gap on uneven ground.
     */
    private fun generateTower(chunk: KryptonChunk, x: Int, z: Int, floorY: Int) {
        val cobble = KryptonBlocks.COBBLESTONE.defaultState
        val battlement = KryptonBlocks.COBBLESTONE_WALL.defaultState
        val air = KryptonBlocks.AIR.defaultState
        val roofY = floorY + TOWER_HEIGHT + 1
        for (dx in 0..4) for (dz in 0..4) {
            val wx = x + dx
            val wz = z + dz
            for (y in surfaceHeight(wx, wz) + 1 until floorY) chunk.setBlock(Vec3i(wx, y, wz), cobble, false) // foundation
            for (y in floorY + 1..roofY + 2) chunk.setBlock(Vec3i(wx, y, wz), air, false) // clear the shaft + any tree above
            chunk.setBlock(Vec3i(wx, floorY, wz), cobble, false) // floor
            val edge = dx == 0 || dx == 4 || dz == 0 || dz == 4
            if (edge) for (y in floorY + 1..floorY + TOWER_HEIGHT) chunk.setBlock(Vec3i(wx, y, wz), cobble, false) // walls
            chunk.setBlock(Vec3i(wx, roofY, wz), cobble, false) // roof platform
            if (edge) chunk.setBlock(Vec3i(wx, roofY + 1, wz), battlement, false) // crenellations around the roof edge
        }
        // A 2-high door opening at the base, in the middle of the +z wall.
        chunk.setBlock(Vec3i(x + 2, floorY + 1, z + 4), air, false)
        chunk.setBlock(Vec3i(x + 2, floorY + 2, z + 4), air, false)
    }

    /** Builds lone ruins at the chunk-local anchor only if the ground is plains/forest and flat enough. */
    private fun maybeGenerateRuins(chunk: KryptonChunk, x: Int, z: Int) {
        val bio = biomeAt(x + 2, z + 2); if (bio == BIOME_DESERT || bio == BIOME_SNOWY) return
        val a = surfaceHeight(x, z); val b = surfaceHeight(x + 4, z); val c = surfaceHeight(x, z + 4)
        val d = surfaceHeight(x + 4, z + 4); val e = surfaceHeight(x + 2, z + 2)
        val maxS = maxOf(a, b, c, d, e); val minS = minOf(a, b, c, d, e)
        if (minS < SEA_LEVEL || maxS - minS > HOUSE_MAX_SLOPE) return
        generateRuins(chunk, x, z, maxS)
    }

    /**
     * Builds small ruins: a 5x5 cobblestone floor at [floorY] with broken, varying-height mossy/cobblestone perimeter
     * walls (0..3 high, deterministic per column) and no roof, plus a guaranteed [RUINS_PILLAR_HEIGHT]-high corner
     * pillar (a clean probe target). A foundation fills any gap on uneven ground.
     */
    private fun generateRuins(chunk: KryptonChunk, x: Int, z: Int, floorY: Int) {
        val cobble = KryptonBlocks.COBBLESTONE.defaultState
        val mossy = KryptonBlocks.MOSSY_COBBLESTONE.defaultState
        val air = KryptonBlocks.AIR.defaultState
        for (dx in 0..4) for (dz in 0..4) {
            val wx = x + dx
            val wz = z + dz
            for (y in surfaceHeight(wx, wz) + 1 until floorY) chunk.setBlock(Vec3i(wx, y, wz), cobble, false) // foundation
            for (y in floorY + 1..floorY + RUINS_PILLAR_HEIGHT + 2) chunk.setBlock(Vec3i(wx, y, wz), air, false) // clear above
            chunk.setBlock(Vec3i(wx, floorY, wz), cobble, false) // floor
            if (dx == 0 || dx == 4 || dz == 0 || dz == 4) {
                val h = oreHash(wx, 1, wz) % 4 // broken wall: 0..3 high
                val mat = if (oreHash(wx, 2, wz) % 2 == 0) mossy else cobble
                for (y in floorY + 1..floorY + h) chunk.setBlock(Vec3i(wx, y, wz), mat, false)
            }
        }
        // A guaranteed standing corner pillar (clean verification target) at the far corner.
        for (y in floorY + 1..floorY + RUINS_PILLAR_HEIGHT) chunk.setBlock(Vec3i(x + 4, y, z + 4), cobble, false)
    }

    /**
     * Deterministic surface height for world column ([x], [z]) — a small multi-octave sine sum giving smooth rolling
     * hills that are continuous across chunk borders (it's a pure function of world coordinates). Not Perlin/biome-aware
     * yet, but real varying terrain instead of a flat plane.
     */
    private fun surfaceHeight(x: Int, z: Int): Int {
        val n = 8.0 * sin(x * 0.043) * cos(z * 0.051) +
            5.0 * sin((x + z) * 0.021) +
            2.5 * cos(x * 0.11) +
            2.5 * sin(z * 0.09)
        return (GEN_BASE_Y + n).toInt().coerceIn(GEN_FLOOR_Y + 3, GEN_MAX_SURFACE_Y)
    }

    /**
     * True if [x],[y],[z] should be carved out as a cave. The near-zero band of a smooth 3D sine field forms
     * winding connected pockets (tunnel/sheet caves); carving ~15% of the stone leaves the terrain mostly intact.
     */
    private fun isCave(x: Int, y: Int, z: Int): Boolean {
        val n = sin(x * 0.08) + sin(y * 0.11) + sin(z * 0.08) + 0.5 * sin((x + z) * 0.05)
        return abs(n) < CAVE_THRESHOLD
    }

    /** Deterministic non-negative hash of a block position, used to scatter ore through stone. */
    private fun oreHash(x: Int, y: Int, z: Int): Int {
        var h = x * 73856093 xor (y * 19349663) xor (z * 83492791)
        h = h xor (h ushr 13)
        return h and 0x7FFFFFFF
    }

    private fun loadData(world: KryptonWorld, pos: ChunkPos, nbt: CompoundTag): KryptonChunk {
        val dataVersion = nbt.getDataVersion()

        // Don't upgrade if the version is not older than our version.
        val data = if (dataVersion < KryptonPlatform.worldVersion) DataConversion.upgrade(nbt, MCTypeRegistry.CHUNK, dataVersion, true) else nbt
        val heightmaps = data.getCompound(HEIGHTMAPS_TAG)
        val biomeRegistry = world.registryHolder.getRegistry(ResourceKeys.BIOME) as? KryptonRegistry<Biome>
            ?: error("Cannot find biome registry in $world!")

        val sectionList = data.getList(SECTIONS_TAG, CompoundTag.ID)
        val sections = arrayOfNulls<ChunkSection>(world.sectionCount())
        for (i in 0 until sectionList.size()) {
            val sectionData = sectionList.getCompound(i)
            val y = sectionData.getByte(Y_TAG).toInt()

            val index = world.getSectionIndexFromSectionY(y)
            if (index >= 0 && index < sections.size) {
                val blocks = if (sectionData.contains(BLOCK_STATES_TAG, CompoundTag.ID)) {
                    PaletteHolder.readBlocks(sectionData.getCompound(BLOCK_STATES_TAG))
                } else {
                    PaletteHolder(PaletteHolder.Strategy.BLOCKS, KryptonBlocks.AIR.defaultState)
                }

                val biomes = if (sectionData.contains(BIOMES_TAG, CompoundTag.ID)) {
                    PaletteHolder.readBiomes(sectionData.getCompound(BIOMES_TAG), biomeRegistry)
                } else {
                    PaletteHolder(PaletteHolder.Strategy.biomes(biomeRegistry), biomeRegistry.get(BiomeKeys.PLAINS)!!)
                }

                val blockLight = if (sectionData.contains(BLOCK_LIGHT_TAG, ByteArrayTag.ID)) sectionData.getByteArray(BLOCK_LIGHT_TAG) else null
                val skyLight = if (sectionData.contains(SKY_LIGHT_TAG, ByteArrayTag.ID)) sectionData.getByteArray(SKY_LIGHT_TAG) else null
                val section = ChunkSection(blocks, biomes, blockLight, skyLight)
                sections[index] = section
            }
        }

        val chunk = KryptonChunk(world, pos, fillMissingSections(world, sections))
        chunk.lastUpdate = data.getLong(LAST_UPDATE_TAG)
        chunk.inhabitedTime = data.getLong(INHABITED_TIME_TAG)

        val toPrime = EnumSet.noneOf(Heightmap.Type::class.java)
        Heightmap.Type.POST_FEATURES.forEach {
            if (heightmaps.contains(it.name, LongArrayTag.ID)) chunk.setHeightmap(it, heightmaps.getLongArray(it.name)) else toPrime.add(it)
        }
        Heightmap.prime(chunk, toPrime)

        // Krypton-specific: container contents stored per-chunk as block entities (see saveData). Side file is a fallback.
        if (data.contains(CONTAINERS_TAG, ListTag.ID)) KryptonChestInventory.loadChunkContainers(data.getList(CONTAINERS_TAG, CompoundTag.ID))

        return chunk
    }

    private fun fillMissingSections(world: KryptonWorld, array: Array<ChunkSection?>): Array<ChunkSection> {
        val result = arrayOfNulls<ChunkSection>(world.sectionCount())
        if (result.size == array.size) {
            System.arraycopy(array, 0, result, 0, result.size)
        } else {
            LOGGER.warn("Failed to set chunk sections! Expected ${result.size} sections, got ${array.size}! Loaded data will not be used.")
        }
        replaceMissingSections(world, result)

        @Suppress("UNCHECKED_CAST") // The replacement replaces any null sections with empty sections, so the array contains no nulls after it.
        return result as Array<ChunkSection>
    }

    override fun loadAllEntities(chunk: KryptonChunk) {
        val nbt = entityRegionManager.read(chunk.x, chunk.z) ?: return

        val dataVersion = nbt.getDataVersion()
        val data = if (dataVersion < KryptonPlatform.worldVersion) DataConversion.upgrade(nbt, MCTypeRegistry.ENTITY_CHUNK, dataVersion) else nbt

        data.getList(ENTITIES_TAG, CompoundTag.ID).forEachCompound {
            val id = it.getString(ENTITY_ID_TAG)
            if (id.isBlank()) return@forEachCompound

            val key = try {
                Key.key(id)
            } catch (_: InvalidKeyException) {
                return@forEachCompound
            }
            val type = KryptonRegistries.ENTITY_TYPE.get(key)

            val entity = EntityFactory.create(type, chunk.world) ?: return@forEachCompound
            entity.load(it)
            chunk.world.entityManager.spawnEntity(entity)
        }
    }

    override fun saveChunk(chunk: KryptonChunk) {
        val data = saveData(chunk)
        regionManager.write(chunk.x, chunk.z, data)
    }

    private fun saveData(chunk: KryptonChunk): CompoundTag {
        val data = buildCompound {
            putInt("DataVersion", KryptonPlatform.worldVersion)
            putLong(LAST_UPDATE_TAG, chunk.lastUpdate)
            putLong(INHABITED_TIME_TAG, chunk.inhabitedTime)
            putString("Status", "full")
            putInt("xPos", chunk.position.x)
            putInt("zPos", chunk.position.z)
        }

        val sectionList = ImmutableListTag.builder(CompoundTag.ID)
        for (i in chunk.minimumLightSection() until chunk.maximumLightSection()) {
            val sectionIndex = chunk.world.getSectionIndexFromSectionY(i)
            // TODO: Handle light sections below and above the world
            if (sectionIndex >= 0 && sectionIndex < chunk.sections().size) {
                val section = chunk.sections()[sectionIndex]
                val sectionData = compound {
                    putByte(Y_TAG, i.toByte())
                    put(BLOCK_STATES_TAG, section.blocks.write { KryptonBlockState.CODEC.encodeStart(it, NbtOps.INSTANCE).result().get() })
                    put(BIOMES_TAG, section.biomes.write { StringTag.of(it.key().asString()) })
                    if (section.blockLight != null) putByteArray(BLOCK_LIGHT_TAG, section.blockLight)
                    if (section.skyLight != null) putByteArray(SKY_LIGHT_TAG, section.skyLight)
                }
                sectionList.add(sectionData)
            }
        }
        data.put(SECTIONS_TAG, sectionList.build())

        val heightmapData = ImmutableCompoundTag.builder()
        chunk.heightmaps().forEach { if (it.key in Heightmap.Type.POST_FEATURES) heightmapData.putLongArray(it.key.name, it.value.rawData()) }
        data.put(HEIGHTMAPS_TAG, heightmapData.build())

        // Krypton-specific: embed this chunk's container contents as block entities, so they persist in the region file.
        KryptonChestInventory.saveChunkContainers(chunk.position.x, chunk.position.z)?.let { data.put(CONTAINERS_TAG, it) }

        return data.build()
    }

    override fun saveAllEntities(chunk: KryptonChunk) {
        val entities = chunk.world.entityTracker.entitiesInChunk(chunk.position)
        if (entities.isEmpty()) return

        val entityList = ImmutableListTag.builder(CompoundTag.ID)
        entities.forEach { if (it !is KryptonPlayer) entityList.add(it.saveWithPassengers().build()) }

        entityRegionManager.write(chunk.x, chunk.z, compound {
            putDataVersion()
            putInts(ENTITY_POSITION_TAG, chunk.position.x, chunk.position.z)
            put(ENTITIES_TAG, entityList.build())
        })
    }

    override fun close() {
        regionManager.close()
        entityRegionManager.close()
    }

    companion object {

        private val LOGGER = LogManager.getLogger()

        // Simple world-gen for chunks with no saved data: bedrock floor + stone/dirt up to a noise surface, grass on top.
        private const val GEN_FLOOR_Y = 55          // bedrock floor
        private const val GEN_BASE_Y = 64           // average surface height (sea-level-ish)
        private const val SEA_LEVEL = 62            // columns whose surface dips below this are flooded with water (lakes)
        private const val GEN_MAX_SURFACE_Y = 100   // cap so noise can't exceed the build area
        private const val TREE_TRUNK_HEIGHT = 5      // oak log height; canopy sits on top
        private const val MAX_TREES_PER_CHUNK = 4    // per-chunk RNG plants 0..this many oaks (natural scatter)
        private const val CAVE_THRESHOLD = 0.35      // |3D sine field| below this is carved as a cave (~15% of stone)
        private const val BIOME_DESERT_THRESHOLD = 0.8 // zone noise above this is desert (sand); else plains (grass+trees)
        private const val BIOME_SUBZONE_THRESHOLD = 0.9 // secondary noise |n|>this carves snowy / forest out of non-desert
        private const val BIOME_PLAINS = 0
        private const val BIOME_DESERT = 1
        private const val BIOME_FOREST = 2
        private const val BIOME_SNOWY = 3
        private const val FOREST_MAX_TREES = 9       // dense oak canopy in forest chunks
        private const val SNOWY_MAX_TREES = 2        // sparse spruce in snowy chunks
        private const val HOUSE_CHANCE_INV = 12      // ~1 in this many chunks is a candidate village-house site
        private const val HOUSE_ANCHOR = 5           // chunk-local near corner of the 5x5 house footprint (5..9, stays in chunk)
        private const val HOUSE_WALL_HEIGHT = 3      // cobblestone wall height; the flat plank roof sits one block above
        private const val HOUSE_MAX_SLOPE = 2        // skip the house if footprint surface varies more than this (too steep)
        private const val WELL_CHANCE_INV = 14       // ~1 in this many chunks is a candidate well site (independent of houses)
        private const val WELL_ANCHOR = 6            // chunk-local near corner of the 4x4 well footprint (6..9, stays in chunk)
        private const val WELL_HEIGHT = 2            // cobblestone corner-post height; the flat roof sits one block above
        private const val TOWER_CHANCE_INV = 24      // ~1 in this many chunks is a lone watchtower (plains/forest)
        private const val TOWER_HEIGHT = 10          // watchtower wall height (tall hollow shaft); roof + battlements above
        private const val DESERT_WELL_CHANCE_INV = 14 // ~1 in this many desert chunks is a sandstone desert well
        private const val RUINS_CHANCE_INV = 26      // ~1 in this many chunks is lone ruins (plains/forest)
        private const val RUINS_PILLAR_HEIGHT = 4    // guaranteed standing corner pillar height (clean probe target)
        private const val VILLAGE_CHANCE_INV = 120   // ~1 in this many hospitable chunks anchors a village (centre well + house ring)
        private const val VILLAGE_NONE = -1
        private const val VILLAGE_WELL = 0           // village centre chunk -> well
        private const val VILLAGE_HOUSE = 1          // chunk neighbouring a centre -> house (the ring)
        private const val LAMP_LOCAL_X = 12          // chunk-local lamp-post spot (clear of 5..9/6..9 footprints + probe columns)
        private const val LAMP_LOCAL_Z = 2
        private const val LAMP_POST_HEIGHT = 3       // oak-fence post height; a lantern sits on top
        private const val AVENUE_LEN = 14            // village path reach (blocks) from the well centre toward each cardinal house

        // Chunk data tags
        private const val HEIGHTMAPS_TAG = "Heightmaps"
        private const val SECTIONS_TAG = "sections"
        private const val Y_TAG = "Y"
        private const val BLOCK_STATES_TAG = "block_states"
        private const val BIOMES_TAG = "biomes"
        private const val BLOCK_LIGHT_TAG = "BlockLight"
        private const val SKY_LIGHT_TAG = "SkyLight"
        private const val LAST_UPDATE_TAG = "LastUpdate"
        private const val INHABITED_TIME_TAG = "InhabitedTime"

        // Entity data tags
        private const val CONTAINERS_TAG = "krypton_containers" // per-chunk container block-entity contents (Krypton-specific)
        private const val ENTITY_ID_TAG = "id"
        private const val ENTITY_POSITION_TAG = "Position"
        private const val ENTITIES_TAG = "Entities"

        @JvmStatic
        private fun replaceMissingSections(world: KryptonWorld, sections: Array<ChunkSection?>) {
            val biomeRegistry = world.registryHolder.getRegistry(ResourceKeys.BIOME) as KryptonRegistry<Biome>
            for (i in sections.indices) {
                if (sections[i] == null) sections[i] = ChunkSection(biomeRegistry)
            }
        }
    }
}
