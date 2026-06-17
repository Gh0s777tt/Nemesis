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
package org.kryptonmc.krypton.inventory

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.kryptonmc.api.item.ItemStack
import org.kryptonmc.api.item.ItemType
import org.kryptonmc.api.item.ItemTypes
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.registry.KryptonRegistries
import org.kryptonmc.krypton.network.buffer.BinaryWriter
import org.kryptonmc.krypton.packet.out.play.PacketOutSetContainerContent
import org.kryptonmc.krypton.packet.out.play.PacketOutSetContainerSlot
import org.kryptonmc.nbt.CompoundTag
import org.kryptonmc.nbt.ListTag
import org.kryptonmc.nbt.compound
import org.kryptonmc.nbt.io.TagCompression
import org.kryptonmc.nbt.io.TagIO
import org.kryptonmc.nbt.list
import java.nio.file.Files
import java.nio.file.Path

/**
 * A generic storage container window of [containerSize] slots (chest=27, double-chest=54, dispenser/dropper=9, ...).
 * Window slots `0 until containerSize` are the container; the rest are the viewer's own inventory (protocol slots
 * 9..44, i.e. windowSlot - [playerOffset]). Contents are keyed by block position and persisted to a side file so
 * they survive server restarts. The cursor is shared with the player inventory ([KryptonPlayerInventory.carried]).
 */
class KryptonChestInventory(
    id: Int,
    private val viewer: KryptonPlayer,
    private val storeKey: String,
    private val containerSize: Int
) : KryptonInventory(id, KryptonInventoryType(Key.key("krypton", "container"), containerSize, Component.text("Container")), containerSize) {

    private val playerOffset = containerSize - 9 // playerProtocolSlot = windowSlot - (containerSize - 9)

    init {
        // Load persisted contents from disk on first use, then this container's stored contents (if any).
        ensureLoaded()
        STORE[storeKey]?.let { stored -> for (i in 0 until containerSize) items.set(i, stored[i]) }
    }

    private fun KryptonItemStack.isEmptySlot(): Boolean =
        this === KryptonItemStack.EMPTY || amount <= 0 || type.key() == ItemTypes.AIR.key()

    private fun getSlot(ws: Int): KryptonItemStack =
        if (ws < containerSize) items.get(ws) else viewer.inventory.getByProtocol(ws - playerOffset)

    private fun setSlot(ws: Int, item: KryptonItemStack) {
        if (ws < containerSize) {
            items.set(ws, item)
            STORE.getOrPut(storeKey) { Array(MAX) { KryptonItemStack.EMPTY } }[ws] = item // persist container contents
        } else {
            viewer.inventory.setSilently(ws - playerOffset, item)
        }
        viewer.connection.send(PacketOutSetContainerSlot(id.toByte(), incrementStateId(), ws.toShort(), item))
    }

    override fun setItem(index: Int, item: ItemStack) {
        if (item is KryptonItemStack && index >= 0 && index < containerSize) setSlot(index, item)
    }

    override fun write(writer: BinaryWriter) {
        writer.writeVarInt(containerSize + PLAYER_SLOTS)
        for (i in 0 until containerSize) writer.writeItem(items.get(i))
        for (i in 0 until PLAYER_SLOTS) writer.writeItem(viewer.inventory.getByProtocol(i + PLAYER_FIRST_PROTOCOL))
    }

    fun handleClick(slot: Int, button: Int, mode: Int) {
        if (slot < 0 || slot >= containerSize + PLAYER_SLOTS) { viewer.inventory.sendCarried(); return }
        // Crafting table: clicking the result slot (9) takes the crafted item and consumes one of each grid ingredient.
        if (isCraftingTable(storeKey) && slot == 9) {
            takeCraftingResult()
            return
        }
        if (mode == 0) {
            val clicked = getSlot(slot)
            val cursor = viewer.inventory.carried
            if (button == 0) { // left click: whole stack
                when {
                    cursor.isEmptySlot() -> { viewer.inventory.carried = clicked; setSlot(slot, KryptonItemStack.EMPTY) }
                    clicked.isEmptySlot() -> { setSlot(slot, cursor); viewer.inventory.carried = KryptonItemStack.EMPTY }
                    clicked.type == cursor.type -> {
                        val max = clicked.type.maximumStackSize
                        val total = clicked.amount + cursor.amount
                        if (total <= max) {
                            setSlot(slot, clicked.withAmount(total)); viewer.inventory.carried = KryptonItemStack.EMPTY
                        } else {
                            setSlot(slot, clicked.withAmount(max)); viewer.inventory.carried = cursor.withAmount(total - max)
                        }
                    }
                    else -> { setSlot(slot, cursor); viewer.inventory.carried = clicked }
                }
            } else if (button == 1) { // right click: half / one
                if (cursor.isEmptySlot()) {
                    if (!clicked.isEmptySlot()) {
                        val half = (clicked.amount + 1) / 2
                        val rest = clicked.amount - half
                        viewer.inventory.carried = clicked.withAmount(half)
                        setSlot(slot, if (rest <= 0) KryptonItemStack.EMPTY else clicked.withAmount(rest))
                    }
                } else when {
                    clicked.isEmptySlot() -> {
                        setSlot(slot, cursor.withAmount(1))
                        viewer.inventory.carried = if (cursor.amount <= 1) KryptonItemStack.EMPTY else cursor.withAmount(cursor.amount - 1)
                    }
                    clicked.type == cursor.type && clicked.amount < clicked.type.maximumStackSize -> {
                        setSlot(slot, clicked.withAmount(clicked.amount + 1))
                        viewer.inventory.carried = if (cursor.amount <= 1) KryptonItemStack.EMPTY else cursor.withAmount(cursor.amount - 1)
                    }
                }
            }
            viewer.inventory.sendCarried()
        } else if (mode == 1) { // shift-click: move between the container and the player's inventory
            quickMove(slot)
        }
        // Crafting table: after any grid change, recompute the result slot (9) from the 3x3 grid (slots 0-8).
        if (isCraftingTable(storeKey)) {
            val grid = (0..8).map { getSlot(it) }
            val result = craftingResultFor(grid)
            if (getSlot(9) !== result) setSlot(9, result)
        }
    }

    /** Takes the crafted result (slot 9) onto the player's cursor and consumes one of each grid ingredient (0-8). */
    private fun takeCraftingResult() {
        val result = getSlot(9)
        if (result.isEmptySlot()) { viewer.inventory.sendCarried(); return }
        val cursor = viewer.inventory.carried
        when {
            cursor.isEmptySlot() -> viewer.inventory.carried = result
            cursor.type == result.type -> viewer.inventory.carried = cursor.withAmount(cursor.amount + result.amount)
            else -> { viewer.inventory.sendCarried(); return } // holding a different item — can't pick the result up
        }
        viewer.inventory.sendCarried()
        for (i in 0..8) {
            val g = getSlot(i)
            if (!g.isEmptySlot()) setSlot(i, if (g.amount <= 1) KryptonItemStack.EMPTY else g.withAmount(g.amount - 1))
        }
        setSlot(9, craftingResultFor((0..8).map { getSlot(it) })) // recompute for the reduced grid (clears if no match)
    }

    private fun quickMove(slot: Int) {
        var remaining = getSlot(slot)
        if (remaining.isEmptySlot()) return
        val target: IntRange = if (slot < containerSize) containerSize until (containerSize + PLAYER_SLOTS) else 0 until containerSize
        val max = remaining.type.maximumStackSize
        for (s in target) {
            if (remaining.isEmptySlot()) break
            val dst = getSlot(s)
            if (!dst.isEmptySlot() && dst.type == remaining.type && dst.amount < max) {
                val moved = minOf(max - dst.amount, remaining.amount)
                setSlot(s, dst.withAmount(dst.amount + moved))
                remaining = if (remaining.amount - moved <= 0) KryptonItemStack.EMPTY else remaining.withAmount(remaining.amount - moved)
            }
        }
        if (!remaining.isEmptySlot()) {
            for (s in target) {
                if (getSlot(s).isEmptySlot()) { setSlot(s, remaining); remaining = KryptonItemStack.EMPTY; break }
            }
        }
        setSlot(slot, remaining)
    }

    /** Sends the full window contents (container slots followed by the player's inventory portion) plus the cursor. */
    fun sendContents() {
        val list = ArrayList<KryptonItemStack>(containerSize + PLAYER_SLOTS)
        for (i in 0 until containerSize) list.add(items.get(i))
        for (i in 0 until PLAYER_SLOTS) list.add(viewer.inventory.getByProtocol(i + PLAYER_FIRST_PROTOCOL))
        viewer.connection.send(PacketOutSetContainerContent(id.toByte(), incrementStateId(), list, viewer.inventory.carried))
    }

    companion object {

        private const val PLAYER_SLOTS = 36     // 27 main + 9 hotbar
        private const val PLAYER_FIRST_PROTOCOL = 9 // window's player portion starts at protocol slot 9
        private const val MAX = 54              // largest supported container (double chest); STORE arrays are this size

        // Container contents keyed by block position string, backed by a side file so they survive server restarts.
        private val STORE = HashMap<String, Array<KryptonItemStack>>()
        private val FILE: Path = Path.of("world", "krypton-chests.dat")
        private var loaded = false

        /**
         * Redstone comparator output (0-15) for the container stored at [storeKey]; 0 if empty or unknown.
         * Approximates vanilla: a non-empty container yields at least 1, scaling toward 15 as it fills.
         */
        @JvmStatic
        fun comparatorSignal(storeKey: String): Int {
            ensureLoaded()
            val arr = STORE[storeKey] ?: return 0
            var fill = 0.0
            var any = false
            for (item in arr) {
                if (item === KryptonItemStack.EMPTY || item.amount <= 0 || item.type.key() == ItemTypes.AIR.key()) continue
                any = true
                fill += item.amount.toDouble() / 64.0 // approximate max stack size
            }
            if (!any) return 0
            return (1.0 + fill / MAX * 14.0).toInt().coerceIn(1, 15)
        }

        /** Removes one item from the first non-empty slot of the container at [storeKey] and returns it (null if empty). */
        @JvmStatic
        fun dispenseOne(storeKey: String): KryptonItemStack? {
            ensureLoaded()
            val arr = STORE[storeKey] ?: return null
            for (i in arr.indices) {
                val item = arr[i]
                if (item === KryptonItemStack.EMPTY || item.amount <= 0 || item.type.key() == ItemTypes.AIR.key()) continue
                arr[i] = if (item.amount <= 1) KryptonItemStack.EMPTY else item.withAmount(item.amount - 1)
                return item.withAmount(1)
            }
            return null
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            if (!Files.exists(FILE)) return
            try {
                val root = TagIO.read(FILE, TagCompression.GZIP)
                root.getList("chests", CompoundTag.ID).forEachCompound { entry ->
                    val arr = Array(MAX) { KryptonItemStack.EMPTY }
                    entry.getList("items", CompoundTag.ID).forEachCompound { tag ->
                        val slot = tag.getByte("Slot").toInt()
                        if (slot in 0 until MAX) arr[slot] = KryptonItemStack.from(tag)
                    }
                    STORE[entry.getString("pos")] = arr
                }
            } catch (ignored: Exception) {
                // Corrupt/unreadable file — start fresh rather than crash.
            }
        }

        /** Writes all stored container contents to the side file (called when a container closes). */
        fun saveToDisk() {
            try {
                val root = compound {
                    put("chests", list {
                        for ((pos, items) in STORE) {
                            add(compound {
                                putString("pos", pos)
                                put("items", list {
                                    items.forEachIndexed { i, item ->
                                        if (item.amount > 0 && item.type.key() != ItemTypes.AIR.key()) {
                                            add(compound {
                                                putByte("Slot", i.toByte())
                                                item.save(this)
                                            })
                                        }
                                    }
                                })
                            })
                        }
                    })
                }
                Files.createDirectories(FILE.parent)
                TagIO.write(FILE, root, TagCompression.GZIP)
            } catch (ignored: Exception) {
                // Best-effort persistence; never let a save failure break gameplay.
            }
        }

        // Single-position store keys look like Vec3i.toString(): "(x, y, z)". Double-chest keys ("D:...") are excluded.
        private val SINGLE_POS_KEY = Regex("""\((-?\d+), (-?\d+), (-?\d+)\)""")

        /**
         * Container contents of the single-position containers in chunk ([chunkX], [chunkZ]), as a vanilla-style
         * block-entity list to embed in that chunk's region NBT. Empty containers and double chests are skipped;
         * returns null if the chunk has none. This is the per-chunk counterpart of the [FILE] side store.
         */
        @JvmStatic
        fun saveChunkContainers(chunkX: Int, chunkZ: Int): ListTag? {
            ensureLoaded()
            val entries = ArrayList<CompoundTag>()
            for ((key, items) in STORE) {
                val match = SINGLE_POS_KEY.matchEntire(key) ?: continue
                val x = match.groupValues[1].toInt()
                val y = match.groupValues[2].toInt()
                val z = match.groupValues[3].toInt()
                if ((x shr 4) != chunkX || (z shr 4) != chunkZ) continue
                if (items.none { it.amount > 0 && it.type.key() != ItemTypes.AIR.key() }) continue // empty — nothing to store
                entries.add(compound {
                    putInt("x", x)
                    putInt("y", y)
                    putInt("z", z)
                    put("items", list {
                        items.forEachIndexed { i, item ->
                            if (item.amount > 0 && item.type.key() != ItemTypes.AIR.key()) {
                                add(compound { putByte("Slot", i.toByte()); item.save(this) })
                            }
                        }
                    })
                })
            }
            if (entries.isEmpty()) return null
            return list { entries.forEach { add(it) } }
        }

        /** Loads single-position container contents from a chunk's region-NBT block-entity list into the in-memory store. */
        @JvmStatic
        fun loadChunkContainers(tag: ListTag) {
            tag.forEachCompound { entry ->
                val x = entry.getInt("x")
                val y = entry.getInt("y")
                val z = entry.getInt("z")
                val arr = Array(MAX) { KryptonItemStack.EMPTY }
                entry.getList("items", CompoundTag.ID).forEachCompound { item ->
                    val slot = item.getByte("Slot").toInt()
                    if (slot in 0 until MAX) arr[slot] = KryptonItemStack.from(item)
                }
                STORE["($x, $y, $z)"] = arr
            }
        }

        // --- Furnace smelting: a furnace is a 3-slot container (0=input, 1=fuel, 2=output) ticked over time. ---
        @JvmField var containerTickScheduled = false // shared by furnaces + hoppers (one scheduled task ticks both)
        private const val COOK_CYCLES = 3 // the tick task runs ~1s; one item smelts every COOK_CYCLES cycles
        private val FURNACE_POSITIONS = HashSet<String>()
        private val cookProgress = HashMap<String, Int>()

        private val SMELT: Map<ItemType, ItemType> by lazy {
            fun item(name: String): ItemType = KryptonRegistries.ITEM.get(Key.key(name))
            mapOf(
                item("raw_iron") to item("iron_ingot"),
                item("raw_copper") to item("copper_ingot"),
                item("raw_gold") to item("gold_ingot"),
                item("iron_ore") to item("iron_ingot"),
                item("sand") to item("glass"),
                item("cobblestone") to item("stone")
            )
        }
        private val FUELS: Set<ItemType> by lazy {
            setOf(
                KryptonRegistries.ITEM.get(Key.key("coal")),
                KryptonRegistries.ITEM.get(Key.key("charcoal")),
                KryptonRegistries.ITEM.get(Key.key("coal_block"))
            )
        }

        private fun isAir(s: KryptonItemStack): Boolean = s.type.key() == ItemTypes.AIR.key() || s.amount <= 0

        fun registerFurnace(posKey: String) { FURNACE_POSITIONS.add(posKey) }

        /** Progresses every known furnace by one cycle: input + fuel -> output once [COOK_CYCLES] cycles elapse. */
        fun tickFurnaces() {
            for (pos in FURNACE_POSITIONS) {
                val arr = STORE[pos] ?: continue
                val input = arr[0]
                val fuel = arr[1]
                val output = arr[2]
                val result = SMELT[input.type]
                if (result == null || isAir(input) || isAir(fuel) || !FUELS.contains(fuel.type)) {
                    cookProgress[pos] = 0
                    continue
                }
                if (!isAir(output) && (output.type != result || output.amount >= result.maximumStackSize)) continue
                val progress = (cookProgress[pos] ?: 0) + 1
                if (progress < COOK_CYCLES) { cookProgress[pos] = progress; continue }
                cookProgress[pos] = 0
                arr[0] = if (input.amount <= 1) KryptonItemStack.EMPTY else input.withAmount(input.amount - 1)
                arr[1] = if (fuel.amount <= 1) KryptonItemStack.EMPTY else fuel.withAmount(fuel.amount - 1)
                arr[2] = if (isAir(output)) KryptonItemStack(result) else output.withAmount(output.amount + 1)
            }
        }

        // --- Brewing: a brewing stand brews the bottle slots (0-2) with the ingredient (slot 3) over time. ---
        private const val BREW_CYCLES = 3
        private val BREW_POSITIONS = HashSet<String>()
        private val brewProgress = HashMap<String, Int>()

        // Real brewing transforms between DISTINCT item types (potion variants are NBT, but these are separate items).
        private val BREW: Map<Pair<ItemType, ItemType>, ItemType> by lazy {
            fun item(name: String): ItemType = KryptonRegistries.ITEM.get(Key.key(name))
            mapOf(
                (item("potion") to item("gunpowder")) to item("splash_potion"),
                (item("splash_potion") to item("dragon_breath")) to item("lingering_potion")
            )
        }

        fun registerBrewingStand(posKey: String) { BREW_POSITIONS.add(posKey) }

        // --- Crafting: a crafting table matches the 3x3 grid (slots 0-8) against shapeless recipes -> result (slot 9). ---
        private val CRAFTING_POSITIONS = HashSet<String>()
        fun registerCraftingTable(posKey: String) { CRAFTING_POSITIONS.add(posKey) }
        fun isCraftingTable(posKey: String): Boolean = posKey in CRAFTING_POSITIONS

        // Shapeless recipes keyed by a canonical signature: sorted "itemKey:count" pairs joined by ',' -> (result, amount).
        private val CRAFT_RECIPES: Map<String, Pair<ItemType, Int>> by lazy {
            fun item(name: String): ItemType = KryptonRegistries.ITEM.get(Key.key(name))
            mapOf(
                "coal:1,stick:1" to (item("torch") to 4),
                "oak_planks:4" to (item("crafting_table") to 1),
                "oak_log:1" to (item("oak_planks") to 4)
            )
        }

        fun craftingSignature(grid: List<KryptonItemStack>): String {
            val counts = sortedMapOf<String, Int>()
            for (it in grid) if (!(it === KryptonItemStack.EMPTY || it.amount <= 0 || it.type.key() == ItemTypes.AIR.key()))
                counts.merge(it.type.key().value(), it.amount) { a, b -> a + b }
            return counts.entries.joinToString(",") { "${it.key}:${it.value}" }
        }

        fun craftingResultFor(grid: List<KryptonItemStack>): KryptonItemStack {
            val recipe = CRAFT_RECIPES[craftingSignature(grid)] ?: return KryptonItemStack.EMPTY
            return KryptonItemStack(recipe.first).withAmount(recipe.second)
        }

        /** Progresses every known brewing stand: ingredient (slot 3) brews the bottles (slots 0-2) once [BREW_CYCLES] elapse. */
        fun tickBrewingStands() {
            for (pos in BREW_POSITIONS) {
                val arr = STORE[pos] ?: continue
                val ingredient = arr[3]
                val brewable = !isAir(ingredient) && (0..2).any { !isAir(arr[it]) && BREW.containsKey(arr[it].type to ingredient.type) }
                if (!brewable) { brewProgress[pos] = 0; continue }
                val progress = (brewProgress[pos] ?: 0) + 1
                if (progress < BREW_CYCLES) { brewProgress[pos] = progress; continue }
                brewProgress[pos] = 0
                for (i in 0..2) {
                    val result = if (!isAir(arr[i])) BREW[arr[i].type to ingredient.type] else null
                    if (result != null) arr[i] = KryptonItemStack(result) // one ingredient brews all matching bottles (vanilla)
                }
                arr[3] = if (ingredient.amount <= 1) KryptonItemStack.EMPTY else ingredient.withAmount(ingredient.amount - 1)
            }
        }

        // --- Hoppers: move one item per tick from the hopper into the container directly below it. ---
        private val HOPPER_TARGETS = HashMap<String, Pair<String, Int>>() // hopperKey -> (belowKey, belowSize)

        fun registerHopper(hopperKey: String, belowKey: String, belowSize: Int) {
            HOPPER_TARGETS[hopperKey] = belowKey to belowSize
        }

        fun tickHoppers() {
            for ((own, target) in HOPPER_TARGETS) {
                val src = STORE[own] ?: continue
                var from = -1
                for (i in 0 until 5) { if (!isAir(src[i])) { from = i; break } }
                if (from < 0) continue
                val moving = src[from]
                val belowKey = target.first
                val belowSize = target.second
                val dst = STORE.getOrPut(belowKey) { Array(MAX) { KryptonItemStack.EMPTY } }
                for (j in 0 until belowSize) {
                    val d = dst[j]
                    if (isAir(d)) {
                        dst[j] = moving.withAmount(1)
                        src[from] = if (moving.amount <= 1) KryptonItemStack.EMPTY else moving.withAmount(moving.amount - 1)
                        break
                    } else if (d.type == moving.type && d.amount < d.type.maximumStackSize) {
                        dst[j] = d.withAmount(d.amount + 1)
                        src[from] = if (moving.amount <= 1) KryptonItemStack.EMPTY else moving.withAmount(moving.amount - 1)
                        break
                    }
                }
            }
        }
    }
}
