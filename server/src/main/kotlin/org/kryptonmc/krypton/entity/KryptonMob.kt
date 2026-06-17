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

import org.kryptonmc.api.entity.EquipmentSlot
import org.kryptonmc.api.entity.MainHand
import org.kryptonmc.api.entity.Mob
import org.kryptonmc.krypton.entity.ai.goal.KryptonGoalSelector
import org.kryptonmc.krypton.entity.ai.pathfinding.KryptonNavigator
import org.kryptonmc.krypton.entity.attribute.AttributeSupplier
import org.kryptonmc.krypton.entity.attribute.KryptonAttributeTypes
import org.kryptonmc.krypton.entity.metadata.MetadataKeys
import org.kryptonmc.krypton.entity.serializer.EntitySerializer
import org.kryptonmc.krypton.entity.serializer.MobSerializer
import org.kryptonmc.krypton.entity.util.EquipmentSlots
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.util.collection.FixedList
import org.kryptonmc.krypton.world.KryptonWorld

@Suppress("LeakingThis")
abstract class KryptonMob(world: KryptonWorld) : KryptonLivingEntity(world), Mob {

    override val serializer: EntitySerializer<out KryptonMob>
        get() = MobSerializer

    internal val handItems = FixedList(2, KryptonItemStack.EMPTY)
    internal val armorItems = FixedList(4, KryptonItemStack.EMPTY)
    internal val handDropChances = FloatArray(2) { DEFAULT_DROP_CHANCE }
    internal val armorDropChances = FloatArray(4) { DEFAULT_DROP_CHANCE }

    final override var canPickUpLoot: Boolean = false
    final override var isPersistent: Boolean = false
    private var target: KryptonLivingEntity? = null

    override val goalSelector: KryptonGoalSelector = KryptonGoalSelector()
    override val navigator: KryptonNavigator = KryptonNavigator(this)

    final override var hasAI: Boolean
        get() = !data.getFlag(MetadataKeys.Mob.FLAGS, FLAG_NO_AI)
        set(value) = data.setFlag(MetadataKeys.Mob.FLAGS, FLAG_NO_AI, !value)
    final override var mainHand: MainHand
        get() = if (data.getFlag(MetadataKeys.Mob.FLAGS, FLAG_LEFT_HANDED)) MainHand.LEFT else MainHand.RIGHT
        set(value) = data.setFlag(MetadataKeys.Mob.FLAGS, FLAG_LEFT_HANDED, value == MainHand.LEFT)
    final override var isAggressive: Boolean
        get() = data.getFlag(MetadataKeys.Mob.FLAGS, FLAG_AGGRESSIVE)
        set(value) = data.setFlag(MetadataKeys.Mob.FLAGS, FLAG_AGGRESSIVE, value)

    init {
        registerGoals()
    }

    override fun defineData() {
        super.defineData()
        data.define(MetadataKeys.Mob.FLAGS, 0)
    }

    override fun getEquipment(slot: EquipmentSlot): KryptonItemStack = when (slot.type) {
        EquipmentSlot.Type.HAND -> handItems.get(EquipmentSlots.index(slot))
        EquipmentSlot.Type.ARMOR -> armorItems.get(EquipmentSlots.index(slot))
    }

    override fun setEquipment(slot: EquipmentSlot, item: KryptonItemStack) {
        when (slot.type) {
            EquipmentSlot.Type.HAND -> handItems.set(EquipmentSlots.index(slot), item)
            EquipmentSlot.Type.ARMOR -> armorItems.set(EquipmentSlots.index(slot), item)
        }
    }

    // TODO: Separate mob interactions
    //protected open fun mobInteract(player: KryptonPlayer, hand: Hand): InteractionResult = InteractionResult.PASS

    /*
    final override fun interact(player: KryptonPlayer, hand: Hand): InteractionResult {
        if (!isAlive) return InteractionResult.PASS
        var result = handleImportantInteractions(player, hand)
        if (result.consumesAction) return result
        result = mobInteract(player, hand)
        if (result.consumesAction) return result
        return super.interact(player, hand)
    }

    private fun handleImportantInteractions(player: KryptonPlayer, hand: Hand): InteractionResult {
        val heldItem = player.heldItem(hand)
        // TODO: Handle mob leashing
        if (heldItem.type === ItemTypes.NAME_TAG) {
            val result = heldItem.type.handler().interactEntity(heldItem, player, this, hand)
            if (result.consumesAction) return result
        }
        // TODO: Handle spawn egg
        return InteractionResult.PASS
    }
    */

    protected open fun registerGoals() {
        // No goals to register by default
    }

    override fun tick(time: Long) {
        super.tick(time)
        if (!isRemoved()) fallToGround() // gravity: drop to the ground if spawned/pushed into the air
        if (!isRemoved() && hasAI) doAiTick(time)
    }

    private fun doAiTick(time: Long) {
        goalSelector.tick(time)
        navigator.tick()
        aiTick()
    }

    private var repathCooldown = 0

    protected open fun aiTick() {
        // Chase AI: pursue the nearest player within follow range. Only HOSTILE mobs give chase — passive
        // animals (friendly categories like CREATURE) don't hunt the player. Prefer the real A* pathfinder
        // (navigator.tick, run each tick in doAiTick, walks the entity along the path we set here); fall back
        // to direct steering toward the player whenever the pathfinder can't build/keep a path (e.g. open ground).
        if (type.category.isFriendly) return
        val nearest = world.players.minByOrNull { it.position.distanceSquared(position) }
        if (nearest == null) {
            setTarget(null)
            return
        }
        val distanceSquared = nearest.position.distanceSquared(position)
        val range = attributes.getValue(KryptonAttributeTypes.FOLLOW_RANGE)
        if (distanceSquared > range * range || distanceSquared < STOP_DISTANCE_SQUARED) {
            setTarget(null) // out of range, or close enough — stop pursuing
            return
        }
        setTarget(nearest)
        val targetVec = nearest.position.asVec3d()
        val hasPath = if (repathCooldown > 0) {
            repathCooldown--
            !navigator.hasReachedTarget() // a path from a recent re-path is still being followed
        } else {
            repathCooldown = REPATH_INTERVAL_TICKS
            navigator.tryPathTo(targetVec) // returns true if A* produced a path
        }
        if (!hasPath) navigator.moveTowards(targetVec, attributes.getValue(KryptonAttributeTypes.MOVEMENT_SPEED))
    }

    fun target(): KryptonLivingEntity? = target

    open fun setTarget(target: KryptonLivingEntity?) {
        this.target = target
    }

    companion object {

        private const val FLAG_NO_AI = 0
        private const val FLAG_LEFT_HANDED = 1
        private const val FLAG_AGGRESSIVE = 2

        private const val STOP_DISTANCE_SQUARED = 1.0 // stop pursuing once within ~1 block of the target
        private const val REPATH_INTERVAL_TICKS = 10 // rebuild the path toward the target twice per second

        private const val DEFAULT_DROP_CHANCE = 0.085F

        @JvmStatic
        fun attributes(): AttributeSupplier.Builder = KryptonLivingEntity.attributes()
            .add(KryptonAttributeTypes.FOLLOW_RANGE, 16.0)
            .add(KryptonAttributeTypes.ATTACK_KNOCKBACK)
    }
}
