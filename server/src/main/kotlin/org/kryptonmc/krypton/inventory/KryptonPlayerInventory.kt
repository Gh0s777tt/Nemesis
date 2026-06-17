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
import org.kryptonmc.api.entity.ArmorSlot
import org.kryptonmc.api.entity.Hand
import org.kryptonmc.api.inventory.PlayerInventory
import org.kryptonmc.api.item.ItemStack
import org.kryptonmc.api.item.ItemTypes
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.item.handler
import org.kryptonmc.krypton.network.buffer.BinaryWriter
import org.kryptonmc.krypton.packet.out.play.PacketOutSetContainerSlot
import org.kryptonmc.krypton.util.collection.FixedList
import org.kryptonmc.krypton.world.block.state.KryptonBlockState
import org.kryptonmc.nbt.ListTag
import org.kryptonmc.nbt.compound
import org.kryptonmc.nbt.list

class KryptonPlayerInventory(override val owner: KryptonPlayer) : KryptonInventory(0, TYPE, SIZE, INVENTORY_SIZE), PlayerInventory {

    override val crafting: MutableList<KryptonItemStack> = FixedList(CRAFTING_SIZE, KryptonItemStack.EMPTY)
    override val armor: MutableList<KryptonItemStack> = FixedList(ARMOR_SIZE, KryptonItemStack.EMPTY)

    override val main: List<ItemStack> = items.subList(HOTBAR_SIZE, INVENTORY_SIZE - 1)
    override val hotbar: List<ItemStack> = items.subList(0, HOTBAR_SIZE - 1)

    override var helmet: ItemStack
        get() = getArmor(ArmorSlot.HELMET)
        set(value) = setArmor(ArmorSlot.HELMET, value)
    override var chestplate: ItemStack
        get() = getArmor(ArmorSlot.CHESTPLATE)
        set(value) = setArmor(ArmorSlot.CHESTPLATE, value)
    override var leggings: ItemStack
        get() = getArmor(ArmorSlot.LEGGINGS)
        set(value) = setArmor(ArmorSlot.LEGGINGS, value)
    override var boots: ItemStack
        get() = getArmor(ArmorSlot.BOOTS)
        set(value) = setArmor(ArmorSlot.BOOTS, value)

    override val mainHand: KryptonItemStack
        get() = items.get(heldSlot)
    override var offHand: KryptonItemStack = KryptonItemStack.EMPTY

    override var heldSlot: Int = 0

    /** The item currently held on the cursor while a container is open (vanilla "carried item"). */
    var carried: KryptonItemStack = KryptonItemStack.EMPTY

    private var dragButton = -1            // -1 = not dragging; 0 = left-drag (even split), 4 = right-drag (one each)
    private val dragSlots = ArrayList<Int>()

    override fun getArmor(slot: ArmorSlot): KryptonItemStack = armor.get(slot.ordinal)

    override fun setArmor(slot: ArmorSlot, item: ItemStack) {
        if (item !is KryptonItemStack) return
        armor.set(slot.ordinal, item)
    }

    override fun getHeldItem(hand: Hand): KryptonItemStack {
        if (hand == Hand.MAIN) return mainHand
        return offHand
    }

    override fun setHeldItem(hand: Hand, item: ItemStack) {
        if (item !is KryptonItemStack) return
        when (hand) {
            Hand.MAIN -> setItem(heldSlot + INVENTORY_SIZE, item)
            Hand.OFF -> setItem(OFFHAND_SLOT, item)
        }
    }

    override fun setItem(index: Int, item: ItemStack) {
        if (item !is KryptonItemStack) return
        setItem(index, item)
    }

    /** Writes a protocol slot WITHOUT sending a window-0 update (used when another window owns the screen). */
    fun setSilently(index: Int, item: KryptonItemStack) {
        when (index) {
            0 -> crafting.set(CRAFTING_SLOT, item)
            in 1..CRAFTING_GRID_SIZE -> crafting.set(index - 1, item)
            in CRAFTING_SIZE until HOTBAR_SIZE -> armor.set(index - CRAFTING_SIZE, item)
            in HOTBAR_SIZE until INVENTORY_SIZE -> items.set(index, item)
            in INVENTORY_SIZE until OFFHAND_SLOT -> items.set(index - INVENTORY_SIZE, item)
            OFFHAND_SLOT -> offHand = item
        }
    }

    fun setItem(index: Int, item: KryptonItemStack) {
        setSilently(index, item)
        owner.connection.send(PacketOutSetContainerSlot(id.toByte(), incrementStateId(), index.toShort(), item))
    }

    /** Reads the item at the given protocol container slot, mirroring [setItem]'s slot mapping. */
    fun getByProtocol(slot: Int): KryptonItemStack = when (slot) {
        0 -> crafting.get(CRAFTING_SLOT)
        in 1..CRAFTING_GRID_SIZE -> crafting.get(slot - 1)
        in CRAFTING_SIZE until HOTBAR_SIZE -> armor.get(slot - CRAFTING_SIZE)
        in HOTBAR_SIZE until INVENTORY_SIZE -> items.get(slot)
        in INVENTORY_SIZE until OFFHAND_SLOT -> items.get(slot - INVENTORY_SIZE)
        OFFHAND_SLOT -> offHand
        else -> KryptonItemStack.EMPTY
    }

    /** Sends the cursor (carried) item to the client using the special window -1, slot -1. */
    fun sendCarried() {
        owner.connection.send(PacketOutSetContainerSlot((-1).toByte(), incrementStateId(), (-1).toShort(), carried))
    }

    // NOTE: KryptonItemStack.isEmpty() is unreliable — it does `type === ItemTypes.AIR`, but ItemTypes.AIR is a
    // registry reference (not the resolved type), so it never matches, and the EMPTY singleton has amount 1.
    // Use a robust emptiness check for click handling.
    private fun KryptonItemStack.isEmptySlot(): Boolean =
        this === KryptonItemStack.EMPTY || amount <= 0 || type.key() == ItemTypes.AIR.key()

    /**
     * Applies a container click for the player inventory (window 0). Currently supports the normal-click mode
     * (left = whole stack pickup/place/swap/merge, right = half pickup / place-one). [setItem] already relays each
     * changed slot to the client; this also resends the cursor.
     */
    fun handleClick(slot: Int, button: Int, mode: Int) {
        if (mode == 5) { handleDrag(slot, button); return } // drag start/end use slot -999, so handle before bounds check
        if (slot < 0 || slot >= SIZE) return
        if (mode == 0) {
            val clicked = getByProtocol(slot)
            val cursor = carried
            if (button == 0) { // left click: act on the whole stack
                when {
                    cursor.isEmptySlot() -> { carried = clicked; setItem(slot, KryptonItemStack.EMPTY) }
                    clicked.isEmptySlot() -> { setItem(slot, cursor); carried = KryptonItemStack.EMPTY }
                    clicked.type == cursor.type -> {
                        val max = clicked.type.maximumStackSize
                        val total = clicked.amount + cursor.amount
                        if (total <= max) {
                            setItem(slot, clicked.withAmount(total)); carried = KryptonItemStack.EMPTY
                        } else {
                            setItem(slot, clicked.withAmount(max)); carried = cursor.withAmount(total - max)
                        }
                    }
                    else -> { setItem(slot, cursor); carried = clicked }
                }
            } else if (button == 1) { // right click: half pickup / place one
                if (cursor.isEmptySlot()) {
                    if (!clicked.isEmptySlot()) {
                        val half = (clicked.amount + 1) / 2
                        val rest = clicked.amount - half
                        carried = clicked.withAmount(half)
                        setItem(slot, if (rest <= 0) KryptonItemStack.EMPTY else clicked.withAmount(rest))
                    }
                } else when {
                    clicked.isEmptySlot() -> {
                        setItem(slot, cursor.withAmount(1))
                        carried = if (cursor.amount <= 1) KryptonItemStack.EMPTY else cursor.withAmount(cursor.amount - 1)
                    }
                    clicked.type == cursor.type && clicked.amount < clicked.type.maximumStackSize -> {
                        setItem(slot, clicked.withAmount(clicked.amount + 1))
                        carried = if (cursor.amount <= 1) KryptonItemStack.EMPTY else cursor.withAmount(cursor.amount - 1)
                    }
                }
            }
            sendCarried()
        } else if (mode == 1) { // shift-click: quick-move between main inventory and hotbar
            quickMove(slot)
        } else if (mode == 6) { // double-click: gather all of the cursor's type into the cursor
            collectAll()
            sendCarried()
        }
    }

    /** Shift-click (mode 1) quick-move: moves the clicked stack between the main inventory and the hotbar. */
    private fun quickMove(slot: Int) {
        var remaining = getByProtocol(slot)
        if (remaining.isEmptySlot()) return
        val target = when (slot) {
            in HOTBAR_SIZE until INVENTORY_SIZE -> INVENTORY_SIZE until OFFHAND_SLOT  // main (9..35) -> hotbar (36..44)
            in INVENTORY_SIZE until OFFHAND_SLOT -> HOTBAR_SIZE until INVENTORY_SIZE  // hotbar (36..44) -> main (9..35)
            else -> return  // crafting/armor/offhand quick-move not supported yet
        }
        val max = remaining.type.maximumStackSize
        // First merge into existing stacks of the same type...
        for (s in target) {
            if (remaining.isEmptySlot()) break
            val dst = getByProtocol(s)
            if (!dst.isEmptySlot() && dst.type == remaining.type && dst.amount < max) {
                val moved = minOf(max - dst.amount, remaining.amount)
                setItem(s, dst.withAmount(dst.amount + moved))
                remaining = if (remaining.amount - moved <= 0) KryptonItemStack.EMPTY else remaining.withAmount(remaining.amount - moved)
            }
        }
        // ...then drop the remainder into the first empty slot of the target region.
        if (!remaining.isEmptySlot()) {
            for (s in target) {
                if (getByProtocol(s).isEmptySlot()) {
                    setItem(s, remaining)
                    remaining = KryptonItemStack.EMPTY
                    break
                }
            }
        }
        setItem(slot, remaining)
    }

    /** Double-click (mode 6): gathers items of the cursor's type from the inventory into the cursor, up to max stack. */
    private fun collectAll() {
        var cursor = carried
        if (cursor.isEmptySlot()) return
        val type = cursor.type
        val max = type.maximumStackSize
        for (s in HOTBAR_SIZE until OFFHAND_SLOT) { // protocol 9..44 (main + hotbar)
            if (cursor.amount >= max) break
            val dst = getByProtocol(s)
            if (!dst.isEmptySlot() && dst.type == type) {
                val take = minOf(max - cursor.amount, dst.amount)
                if (take > 0) {
                    cursor = cursor.withAmount(cursor.amount + take)
                    setItem(s, if (dst.amount - take <= 0) KryptonItemStack.EMPTY else dst.withAmount(dst.amount - take))
                }
            }
        }
        carried = cursor
    }

    /** Drag / "paint" (mode 5): button 0/2 = start/end left-drag (even split), 4/6 = start/end right-drag (one each). */
    private fun handleDrag(slot: Int, button: Int) {
        when (button) {
            0 -> { dragButton = 0; dragSlots.clear() }
            4 -> { dragButton = 4; dragSlots.clear() }
            1 -> if (dragButton == 0 && slot in 0 until SIZE && slot !in dragSlots) dragSlots.add(slot)
            5 -> if (dragButton == 4 && slot in 0 until SIZE && slot !in dragSlots) dragSlots.add(slot)
            2 -> { if (dragButton == 0) applyDrag(true); dragButton = -1; dragSlots.clear() }
            6 -> { if (dragButton == 4) applyDrag(false); dragButton = -1; dragSlots.clear() }
        }
        sendCarried()
    }

    private fun applyDrag(even: Boolean) {
        val cursor = carried
        if (cursor.isEmptySlot() || dragSlots.isEmpty()) return
        val max = cursor.type.maximumStackSize
        val per = if (even) maxOf(1, cursor.amount / dragSlots.size) else 1
        var remaining = cursor.amount
        for (s in dragSlots) {
            if (remaining <= 0) break
            val dst = getByProtocol(s)
            val give = when {
                dst.isEmptySlot() -> minOf(per, remaining, max)
                dst.type == cursor.type && dst.amount < max -> minOf(per, remaining, max - dst.amount)
                else -> 0
            }
            if (give <= 0) continue
            setItem(s, if (dst.isEmptySlot()) cursor.withAmount(give) else dst.withAmount(dst.amount + give))
            remaining -= give
        }
        carried = if (remaining <= 0) KryptonItemStack.EMPTY else cursor.withAmount(remaining)
    }

    override fun write(writer: BinaryWriter) {
        writer.writeVarInt(SIZE)
        writer.writeItem(crafting.get(CRAFTING_SIZE - 1))
        for (i in 0 until CRAFTING_GRID_SIZE) {
            writer.writeItem(crafting.get(i))
        }
        armor.forEach(writer::writeItem)
        for (i in 0 until MAIN_SIZE) {
            writer.writeItem(items.get(i + HOTBAR_SIZE))
        }
        for (i in 0 until HOTBAR_SIZE) {
            writer.writeItem(items.get(i))
        }
        writer.writeItem(offHand)
    }

    fun getDestroySpeed(state: KryptonBlockState): Float {
        val item = items.get(heldSlot)
        return item.type.handler().destroySpeed(item, state)
    }

    fun load(data: ListTag) {
        clear()
        data.forEachCompound {
            // No point creating the item stack just to throw it away if it's air
            if (it.getString("id") == ItemTypes.AIR.key().asString()) return@forEachCompound
            val slot = it.getByte("Slot").toInt()
            val stack = KryptonItemStack.from(it)
            when (slot) {
                in items.indices -> items.set(slot, stack)
                BOOTS_DATA_SLOT -> armor.set(BOOTS_SLOT, stack)
                LEGGINGS_DATA_SLOT -> armor.set(LEGGINGS_SLOT, stack)
                CHESTPLATE_DATA_SLOT -> armor.set(CHESTPLATE_SLOT, stack)
                HELMET_DATA_SLOT -> armor.set(HELMET_SLOT, stack)
                OFFHAND_DATA_SLOT -> offHand = stack
            }
        }
    }

    fun save(): ListTag = list {
        items.forEachIndexed { index, item ->
            if (item.type === ItemTypes.AIR) return@forEachIndexed
            add(compound {
                putByte("Slot", index.toByte())
                item.save(this)
            })
        }
        var i: Byte = HELMET_DATA_SLOT.toByte()
        armor.forEach {
            if (it.type === ItemTypes.AIR) return@forEach
            add(compound {
                putByte("Slot", i--)
                it.save(this)
            })
        }
        if (offHand.type !== ItemTypes.AIR) {
            add(compound {
                putByte("Slot", OFFHAND_DATA_SLOT.toByte())
                offHand.save(this)
            })
        }
    }

    companion object {

        const val SIZE: Int = 46
        private const val MAIN_SIZE = 27
        private const val HOTBAR_SIZE = 9
        private const val INVENTORY_SIZE = MAIN_SIZE + HOTBAR_SIZE
        private const val CRAFTING_SIZE = 5
        private const val CRAFTING_GRID_SIZE = 4
        private const val CRAFTING_SLOT = 4
        private const val OFFHAND_SLOT = SIZE - 1
        private const val OFFHAND_DATA_SLOT = -106
        private const val ARMOR_SIZE = 4
        private const val HELMET_SLOT = 0
        private const val HELMET_DATA_SLOT = 103
        private const val CHESTPLATE_SLOT = 1
        private const val CHESTPLATE_DATA_SLOT = 102
        private const val LEGGINGS_SLOT = 2
        private const val LEGGINGS_DATA_SLOT = 101
        private const val BOOTS_SLOT = 3
        private const val BOOTS_DATA_SLOT = 100

        private val TYPE = KryptonInventoryType(Key.key("krypton", "inventory/player"), SIZE, Component.translatable("container.inventory"))
    }
}
