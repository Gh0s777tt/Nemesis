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
import org.kryptonmc.krypton.coordinate.Positioning
import org.kryptonmc.krypton.entity.ai.goal.KryptonGoalSelector
import org.kryptonmc.krypton.entity.ai.pathfinding.KryptonNavigator
import org.kryptonmc.krypton.entity.attribute.AttributeSupplier
import org.kryptonmc.krypton.entity.attribute.KryptonAttributeTypes
import org.kryptonmc.krypton.entity.metadata.MetadataKeys
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.entity.serializer.EntitySerializer
import org.kryptonmc.krypton.entity.serializer.MobSerializer
import org.kryptonmc.krypton.entity.util.EquipmentSlots
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.packet.out.play.PacketOutSetEntityVelocity
import org.kryptonmc.krypton.util.collection.FixedList
import org.kryptonmc.krypton.world.KryptonWorld
import kotlin.math.sqrt

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
    private var attackCooldown = 0

    protected open fun aiTick() {
        // Chase AI: pursue the nearest player within follow range. Only HOSTILE mobs give chase — passive
        // animals (friendly categories like CREATURE) don't hunt the player. Prefer the real A* pathfinder
        // (navigator.tick, run each tick in doAiTick, walks the entity along the path we set here); fall back
        // to direct steering toward the player whenever the pathfinder can't build/keep a path (e.g. open ground).
        if (type.category.isFriendly) return
        if (attackCooldown > 0) attackCooldown--
        val nearest = world.players.minByOrNull { it.position.distanceSquared(position) }
        if (nearest == null) {
            setTarget(null)
            return
        }
        val distanceSquared = nearest.position.distanceSquared(position)
        val range = attributes.getValue(KryptonAttributeTypes.FOLLOW_RANGE)
        if (distanceSquared > range * range) {
            setTarget(null) // out of follow range — stop pursuing
            return
        }
        setTarget(nearest)
        // Within melee reach: bite the player on a cooldown instead of pathing closer. Death/respawn at 0 HP is
        // handled by the existing flow (the victim's client shows the death screen and requests a respawn).
        if (distanceSquared <= ATTACK_REACH_SQUARED) {
            if (attackCooldown == 0 && nearest.health > 0F) {
                attackTarget(nearest)
                attackCooldown = ATTACK_COOLDOWN_TICKS
            }
            return
        }
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

    /** Deal one melee hit to [target]: reduce its health (the setter pushes Update Health) and knock it back. */
    private fun attackTarget(target: KryptonPlayer) {
        target.health = (target.health - MOB_ATTACK_DAMAGE).coerceAtLeast(0F)
        val dx = target.position.x - position.x
        val dz = target.position.z - position.z
        val dist = sqrt(dx * dx + dz * dz)
        val (vx, vz) = if (dist > 1.0E-4) (dx / dist) * KNOCKBACK_HORIZONTAL to (dz / dist) * KNOCKBACK_HORIZONTAL else 0.0 to 0.0
        val velocity = PacketOutSetEntityVelocity(target.id, Positioning.encodeVelocity(vx), Positioning.encodeVelocity(KNOCKBACK_VERTICAL), Positioning.encodeVelocity(vz))
        target.connection.send(velocity)     // the victim's own client applies the knockback
        target.sendPacketToViewers(velocity)  // everyone else sees them fly back
    }

    fun target(): KryptonLivingEntity? = target

    open fun setTarget(target: KryptonLivingEntity?) {
        this.target = target
    }

    companion object {

        private const val FLAG_NO_AI = 0
        private const val FLAG_LEFT_HANDED = 1
        private const val FLAG_AGGRESSIVE = 2

        private const val ATTACK_REACH_SQUARED = 2.25 // within ~1.5 blocks the mob bites instead of pathing closer
        private const val ATTACK_COOLDOWN_TICKS = 20  // ~1 hit per second, like vanilla melee mobs
        private const val MOB_ATTACK_DAMAGE = 3F      // ~1.5 hearts per hit (zombie-ish on normal difficulty)
        private const val KNOCKBACK_HORIZONTAL = 0.4  // horizontal shove per hit
        private const val KNOCKBACK_VERTICAL = 0.36   // slight upward pop (matches PvP knockback)
        private const val REPATH_INTERVAL_TICKS = 10 // rebuild the path toward the target twice per second

        private const val DEFAULT_DROP_CHANCE = 0.085F

        @JvmStatic
        fun attributes(): AttributeSupplier.Builder = KryptonLivingEntity.attributes()
            .add(KryptonAttributeTypes.FOLLOW_RANGE, 16.0)
            .add(KryptonAttributeTypes.ATTACK_KNOCKBACK)
    }
}
