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
package org.kryptonmc.krypton.network.handlers

import com.mojang.brigadier.StringReader
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.Translator
import org.apache.logging.log4j.LogManager
import org.kryptonmc.api.entity.Hand
import org.kryptonmc.api.resource.ResourcePack
import org.kryptonmc.api.util.Position
import org.kryptonmc.api.util.Vec3d
import org.kryptonmc.api.world.GameMode
import org.kryptonmc.krypton.KryptonServer
import org.kryptonmc.krypton.command.CommandSigningContext
import org.kryptonmc.krypton.commands.KryptonPermission
import org.kryptonmc.krypton.entity.player.KryptonPlayer
import org.kryptonmc.krypton.entity.player.KryptonPlayerSettings
import org.kryptonmc.krypton.entity.player.KryptonSkinParts
import org.kryptonmc.krypton.entity.player.PlayerPublicKey
import org.kryptonmc.krypton.entity.player.RespawnData
import org.kryptonmc.krypton.entity.projectile.KryptonFishingHook
import org.kryptonmc.krypton.entity.projectile.KryptonThrownPotion
import org.kryptonmc.krypton.event.command.KryptonCommandExecuteEvent
import org.kryptonmc.krypton.event.player.KryptonPlayerChatEvent
import org.kryptonmc.krypton.event.player.KryptonPlayerMoveEvent
import org.kryptonmc.krypton.event.player.interact.KryptonPlayerPlaceBlockEvent
import org.kryptonmc.krypton.event.player.KryptonPluginMessageReceivedEvent
import org.kryptonmc.krypton.event.player.KryptonPlayerResourcePackStatusEvent
import org.kryptonmc.krypton.entity.EntityFactory
import org.kryptonmc.krypton.entity.KryptonExperienceOrb
import org.kryptonmc.krypton.entity.ExperienceOrbManager
import org.kryptonmc.krypton.entity.animal.KryptonAnimal
import org.kryptonmc.krypton.entity.KryptonLivingEntity
import org.kryptonmc.krypton.entity.CropManager
import org.kryptonmc.krypton.entity.MobMover
import org.kryptonmc.krypton.entity.PlayerEffectManager
import org.kryptonmc.krypton.entity.Pose
import org.kryptonmc.krypton.entity.metadata.MetadataKeys
import org.kryptonmc.krypton.entity.ItemDropManager
import org.kryptonmc.krypton.entity.KryptonItemEntity
import org.kryptonmc.krypton.entity.KryptonPrimedTnt
import org.kryptonmc.krypton.packet.out.play.PacketOutSynchronizePlayerPosition
import org.kryptonmc.krypton.packet.out.play.PacketOutSetEntityVelocity
import org.kryptonmc.krypton.packet.out.play.PacketOutRespawn
import org.kryptonmc.krypton.coordinate.Positioning
import org.kryptonmc.krypton.registry.KryptonDynamicRegistries
import org.kryptonmc.krypton.world.biome.BiomeManager
import org.kryptonmc.krypton.world.KryptonWorld
import net.kyori.adventure.key.Key
import kotlin.math.floor
import kotlin.math.sqrt
import org.kryptonmc.krypton.entity.animal.KryptonCow
import org.kryptonmc.krypton.entity.animal.KryptonSheep
import org.kryptonmc.api.item.data.DyeColor
import org.kryptonmc.krypton.inventory.KryptonChestInventory
import org.kryptonmc.krypton.inventory.KryptonPlayerInventory
import org.kryptonmc.krypton.item.KryptonItemStack
import org.kryptonmc.krypton.item.handler
import org.kryptonmc.krypton.item.handler.FoodHandler
import org.kryptonmc.krypton.item.handler.ItemTimedHandler
import org.kryptonmc.krypton.item.meta.KryptonItemMeta
import org.kryptonmc.nbt.EndTag
import org.kryptonmc.nbt.ImmutableCompoundTag
import org.kryptonmc.nbt.MutableListTag
import org.kryptonmc.krypton.network.chat.ChatUtil
import org.kryptonmc.krypton.network.chat.RemoteChatSession
import org.kryptonmc.krypton.network.chat.SignableCommand
import org.kryptonmc.krypton.network.chat.SignedMessageChain
import org.kryptonmc.krypton.packet.`in`.play.PacketInAbilities
import org.kryptonmc.krypton.packet.`in`.play.PacketInChatCommand
import org.kryptonmc.krypton.packet.`in`.play.PacketInChat
import org.kryptonmc.krypton.packet.`in`.play.PacketInChatSessionUpdate
import org.kryptonmc.krypton.packet.`in`.play.PacketInClickContainer
import org.kryptonmc.krypton.packet.`in`.play.PacketInClientCommand
import org.kryptonmc.krypton.packet.`in`.play.PacketInCloseContainer
import org.kryptonmc.krypton.packet.`in`.play.PacketInClientInformation
import org.kryptonmc.krypton.packet.`in`.play.PacketInCommandSuggestionsRequest
import org.kryptonmc.krypton.packet.`in`.play.PacketInInteract
import org.kryptonmc.krypton.packet.`in`.play.PacketInKeepAlive
import org.kryptonmc.krypton.packet.`in`.play.PacketInPlayerAction
import org.kryptonmc.krypton.packet.`in`.play.PacketInPlayerAction.Action as PlayerAction
import org.kryptonmc.krypton.packet.`in`.play.PacketInPlayerCommand
import org.kryptonmc.krypton.packet.`in`.play.PacketInPlayerCommand.Action as EntityAction
import org.kryptonmc.krypton.packet.`in`.play.PacketInPlayerInput
import org.kryptonmc.krypton.packet.`in`.play.PacketInPluginMessage
import org.kryptonmc.krypton.packet.`in`.play.PacketInQueryEntityTag
import org.kryptonmc.krypton.packet.`in`.play.PacketInResourcePack
import org.kryptonmc.krypton.packet.`in`.play.PacketInSetCreativeModeSlot
import org.kryptonmc.krypton.packet.`in`.play.PacketInSetHeldItem
import org.kryptonmc.krypton.packet.`in`.play.PacketInSetPlayerPosition
import org.kryptonmc.krypton.packet.`in`.play.PacketInSetPlayerPositionAndRotation
import org.kryptonmc.krypton.packet.`in`.play.PacketInSetPlayerRotation
import org.kryptonmc.krypton.packet.`in`.play.PacketInSwingArm
import org.kryptonmc.krypton.packet.`in`.play.PacketInUseItem
import org.kryptonmc.krypton.packet.`in`.play.PacketInUseItemOn
import org.kryptonmc.krypton.packet.out.play.EntityAnimations
import org.kryptonmc.krypton.packet.out.play.PacketOutAnimation
import org.kryptonmc.krypton.packet.out.play.PacketOutCommandSuggestionsResponse
import org.kryptonmc.krypton.packet.out.play.PacketOutDisconnect
import org.kryptonmc.krypton.packet.out.play.PacketOutAcknowledgeBlockChange
import org.kryptonmc.krypton.packet.out.play.PacketOutOpenScreen
import org.kryptonmc.krypton.packet.out.play.PacketOutBlockUpdate
import org.kryptonmc.krypton.packet.out.play.PacketOutEntityEffect
import org.kryptonmc.krypton.packet.out.play.PacketOutUpdateTime
import org.kryptonmc.krypton.packet.out.play.PacketOutKeepAlive
import org.kryptonmc.krypton.packet.out.play.PacketOutPlayerInfoUpdate
import org.kryptonmc.krypton.packet.out.play.PacketOutTagQueryResponse
import org.kryptonmc.krypton.registry.KryptonRegistries
import org.kryptonmc.krypton.util.crypto.SignatureValidator
import org.kryptonmc.api.scheduling.ExecutionType
import org.kryptonmc.api.scheduling.TaskTime
import org.kryptonmc.api.util.Vec3i
import org.kryptonmc.krypton.world.block.KryptonBlocks
import org.kryptonmc.krypton.world.block.state.KryptonBlockState
import org.kryptonmc.krypton.world.chunk.KryptonChunk
import org.kryptonmc.krypton.state.property.KryptonProperties
import org.kryptonmc.krypton.state.property.KryptonProperty
import org.kryptonmc.api.block.meta.DoubleBlockHalf
import org.kryptonmc.api.block.meta.ComparatorMode
import org.kryptonmc.krypton.coordinate.ChunkPos
import org.kryptonmc.krypton.entity.KryptonEntity
import org.kryptonmc.krypton.event.player.action.KryptonPlayerStartSneakingEvent
import org.kryptonmc.krypton.event.player.action.KryptonPlayerStartSprintingEvent
import org.kryptonmc.krypton.event.player.action.KryptonPlayerStopSneakingEvent
import org.kryptonmc.krypton.event.player.action.KryptonPlayerStopSprintingEvent
import org.kryptonmc.krypton.event.player.interact.KryptonPlayerAttackEntityEvent
import org.kryptonmc.krypton.event.player.interact.KryptonPlayerInteractAtEntityEvent
import org.kryptonmc.krypton.event.player.interact.KryptonPlayerInteractWithEntityEvent
import org.kryptonmc.krypton.locale.DisconnectMessages
import org.kryptonmc.krypton.locale.MinecraftTranslationManager
import org.kryptonmc.krypton.network.NioConnection
import org.kryptonmc.krypton.network.PacketGrouping
import java.time.Duration

/**
 * This is the largest and most important of the four packet handlers, as the
 * play state is where the vast majority of packets reside.
 *
 * As mentioned above, this is the packet handler for the
 * [Play][org.kryptonmc.krypton.packet.PacketState.PLAY] state.
 *
 * This handles all supported inbound packets in the play state.
 */
class PlayPacketHandler(
    private val server: KryptonServer,
    private val connection: NioConnection,
    private val player: KryptonPlayer
) : TickablePacketHandler {

    private val chatTracker = player.chatTracker
    private var chatSession: RemoteChatSession? = null

    private var lastKeepAlive = System.currentTimeMillis()
    private var keepAliveChallenge = 0L
    private var pendingKeepAlive = false

    override fun tick() {
        val time = System.currentTimeMillis()
        if (time - lastKeepAlive < KEEP_ALIVE_INTERVAL) return
        if (pendingKeepAlive) {
            disconnect(DisconnectMessages.TIMEOUT)
            return
        }
        pendingKeepAlive = true
        lastKeepAlive = time
        keepAliveChallenge = time
        connection.send(PacketOutKeepAlive(keepAliveChallenge))
    }

    private fun disconnect(reason: Component) {
        connection.send(PacketOutDisconnect(reason))
        connection.disconnect(reason)
    }

    override fun onDisconnect(message: Component?) {
        if (message != null) {
            val translated = MinecraftTranslationManager.render(message)
            LOGGER.info("${player.name} was disconnected: ${PlainTextComponentSerializer.plainText().serialize(translated)}")
        }
        player.onDisconnect()
        server.playerManager.removePlayer(player)
    }

    fun handleSwingArm(packet: PacketInSwingArm) {
        val animation = when (packet.hand) {
            Hand.MAIN -> EntityAnimations.SWING_MAIN_ARM
            Hand.OFF -> EntityAnimations.SWING_OFFHAND
        }
        PacketGrouping.sendGroupedPacket(server, PacketOutAnimation(player.id, animation)) { it !== player }
    }

    fun handleChatCommand(packet: PacketInChatCommand) {
        if (!ChatUtil.isValidMessage(packet.command)) disconnect(DisconnectMessages.ILLEGAL_CHARACTERS)

        val event = server.eventNode.fire(KryptonCommandExecuteEvent(player, packet.command))
        if (!event.isAllowed()) return

        val command = event.result?.command ?: packet.command
        val lastSeen = chatTracker.handleChat(command, packet.timestamp, packet.lastSeenMessages) ?: return

        val source = player.createCommandSourceStack()
        val parsed = server.commandManager.parse(source, command)
        val arguments = try {
            chatTracker.collectSignedArguments(packet, SignableCommand.of(parsed), lastSeen)
        } catch (exception: SignedMessageChain.DecodeException) {
            handleMessageDecodeFailure(exception)
            return
        }

        server.commandManager.dispatch(source.withSigningContext(CommandSigningContext.SignedArguments(arguments)), command)
    }

    fun handleChat(packet: PacketInChat) {
        // Sanity check message content
        if (!ChatUtil.isValidMessage(packet.message)) disconnect(DisconnectMessages.ILLEGAL_CHARACTERS)

        // Fire the chat event
        val event = server.eventNode.fire(KryptonPlayerChatEvent(player, packet.message))
        if (!event.isAllowed()) return

        val lastSeen = chatTracker.handleChat(packet.message, packet.timestamp, packet.lastSeenMessages) ?: return
        val message = try {
            chatTracker.getSignedMessage(packet, lastSeen)
        } catch (exception: SignedMessageChain.DecodeException) {
            handleMessageDecodeFailure(exception)
            return
        }

        val unsignedContent = event.result?.message ?: message.decoratedContent()
        chatTracker.addMessageToChain(message, unsignedContent)
    }

    private fun handleMessageDecodeFailure(exception: SignedMessageChain.DecodeException) {
        if (exception.shouldDisconnect) {
            disconnect(exception.asComponent())
        } else {
            player.sendSystemMessage(exception.asComponent().color(NamedTextColor.RED))
        }
    }

    fun handleChatSessionUpdate(packet: PacketInChatSessionUpdate) {
        val session = packet.chatSession
        val currentKey = chatSession?.publicKey?.data
        val newKey = session.publicKey
        if (currentKey == newKey) return // Nothing to update
        if (currentKey != null && newKey.expiryTime.isBefore(currentKey.expiryTime)) {
            disconnect(PlayerPublicKey.EXPIRED_KEY)
            return
        }

        val newSession = try {
            session.validate(player.profile, SignatureValidator.YGGDRASIL, Duration.ZERO)
        } catch (exception: PlayerPublicKey.ValidationException) {
            LOGGER.error("Failed to validate public key!", exception)
            disconnect(exception.asComponent())
            return
        }
        chatSession = newSession
        chatTracker.resetPlayerChatState(newSession)
    }

    fun handleClientInformation(packet: PacketInClientInformation) {
        player.settings = KryptonPlayerSettings(
            Translator.parseLocale(packet.locale),
            packet.viewDistance.toInt(),
            packet.chatVisibility,
            packet.chatColors,
            KryptonSkinParts(packet.skinSettings.toInt()),
            packet.mainHand,
            packet.filterText,
            packet.allowsListing
        )
    }

    fun handleSetCreativeModeSlot(packet: PacketInSetCreativeModeSlot) {
        if (player.gameMode != GameMode.CREATIVE) return
        val item = packet.clickedItem
        val slot = packet.slot.toInt()
        val inValidRange = slot >= 1 && slot < KryptonPlayerInventory.SIZE
        val isValid = item.isEmpty() || item.meta.damage >= 0 && item.amount <= 64 && !item.isEmpty()
        if (inValidRange && isValid) player.inventory.setItem(slot, packet.clickedItem)
    }

    fun handleClickContainer(packet: PacketInClickContainer) {
        val windowId = packet.containerId.toInt()
        if (windowId == 0) {
            player.inventory.handleClick(packet.slot.toInt(), packet.button.toInt(), packet.mode)
            return
        }
        val open = player.openInventory
        if (open is KryptonChestInventory && open.id == windowId) {
            open.handleClick(packet.slot.toInt(), packet.button.toInt(), packet.mode)
            reevaluateComparators(player.world) // a comparator reading this container must refresh its output
        }
    }

    fun handleCloseContainer(packet: PacketInCloseContainer) {
        // TODO: vanilla drops the cursor item into the world on close; for now we just clear it.
        player.inventory.carried = KryptonItemStack.EMPTY
        player.openInventory = null
        KryptonChestInventory.saveToDisk() // persist chest contents to disk
    }

    private fun openContainer(storeKey: String, containerSize: Int, menuType: Int) {
        // One open container at a time (window id 1); contents persist by block position.
        val container = KryptonChestInventory(1, player, storeKey, containerSize)
        player.openInventory = container
        player.connection.send(PacketOutOpenScreen(container.id, menuType, Component.text("Container")))
        container.sendContents()
    }

    private fun ensureContainerTick() {
        if (KryptonChestInventory.containerTickScheduled) return
        KryptonChestInventory.containerTickScheduled = true
        server.scheduler.buildTask { KryptonChestInventory.tickFurnaces(); KryptonChestInventory.tickHoppers(); KryptonChestInventory.tickBrewingStands(); MobMover.tick(); ItemDropManager.tick(); ExperienceOrbManager.tick(); CropManager.tick(); PlayerEffectManager.tick() }
            .delay(TaskTime.ticks(20)).period(TaskTime.ticks(20))
            .executionType(ExecutionType.SYNCHRONOUS).schedule()
    }

    private fun openFurnace(storeKey: String) {
        KryptonChestInventory.registerFurnace(storeKey)
        ensureContainerTick()
        openContainer(storeKey, 3, 13) // 13 = furnace menu type (generic 3-slot furnace screen)
    }

    fun handlePlayerCommand(packet: PacketInPlayerCommand) {
        when (packet.action) {
            EntityAction.START_SNEAKING -> {
                if (!server.eventNode.fire(KryptonPlayerStartSneakingEvent(player)).isAllowed()) return
                player.isSneaking = true
            }
            EntityAction.STOP_SNEAKING -> {
                if (!server.eventNode.fire(KryptonPlayerStopSneakingEvent(player)).isAllowed()) return
                player.isSneaking = false
            }
            EntityAction.START_SPRINTING -> {
                if (!server.eventNode.fire(KryptonPlayerStartSprintingEvent(player)).isAllowed()) return
                player.isSprinting = true
            }
            EntityAction.STOP_SPRINTING -> {
                if (!server.eventNode.fire(KryptonPlayerStopSprintingEvent(player)).isAllowed()) return
                player.isSprinting = false
            }
            EntityAction.STOP_SLEEPING -> Unit // TODO: Sleeping
            EntityAction.START_HORSE_JUMP, EntityAction.STOP_HORSE_JUMP -> Unit // TODO: Horse jumping
            EntityAction.OPEN_INVENTORY -> Unit // TODO: Open vehicle inventory
            EntityAction.START_GLIDING -> if (!player.tryStartGliding()) player.stopGliding()
        }
    }

    fun handleSetHeldItem(packet: PacketInSetHeldItem) {
        val slot = packet.slot.toInt()
        if (slot < 0 || slot > 8) {
            LOGGER.warn("${player.profile.name} tried to change their held item slot to an invalid value!")
            return
        }
        player.inventory.heldSlot = slot
    }

    fun handleKeepAlive(packet: PacketInKeepAlive) {
        if (pendingKeepAlive && packet.id == keepAliveChallenge) {
            connection.updateLatency(lastKeepAlive)
            pendingKeepAlive = false
            PacketGrouping.sendGroupedPacket(server, PacketOutPlayerInfoUpdate(PacketOutPlayerInfoUpdate.Action.UPDATE_LATENCY, player))
            return
        }
        disconnect(DisconnectMessages.TIMEOUT)
    }

    fun handleAbilities(packet: PacketInAbilities) {
        player.abilities.flying = packet.isFlying && player.abilities.canFly
    }

    // TODO: This entire thing needs to be rewritten
    fun handleUseItemOn(packet: PacketInUseItemOn) {
        val world = player.world
        val position = packet.hitResult.position
        // Use the CLICKED block's chunk, not the player's — otherwise chunk.getBlock/setBlock mask the global
        // position with `& 15` and read/write the wrong chunk when the target is outside the player's own chunk.
        val chunk = world.chunkManager.getChunk(ChunkPos(position.x shr 4, position.z shr 4)) ?: return
        val existingBlock = chunk.getBlock(position)
        // Right-clicking a chest opens a container window instead of placing a block.
        // Right-clicking a container block opens its window (size + menu type depend on the block type).
        if (existingBlock.eq(KryptonBlocks.FURNACE)) {
            openFurnace(position.toString())
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (existingBlock.eq(KryptonBlocks.CRAFTING_TABLE)) {
            // Grid = container slots 0-8, result = slot 9; opened as a 27-slot generic container for the UI.
            KryptonChestInventory.registerCraftingTable(position.toString())
            openContainer(position.toString(), 27, 2)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (existingBlock.eq(KryptonBlocks.BREWING_STAND)) {
            // Brews bottle slots (0-2) with the ingredient (slot 3) over time; opened as a generic container for the UI.
            KryptonChestInventory.registerBrewingStand(position.toString())
            ensureContainerTick() // the shared periodic tick drives tickBrewingStands()
            openContainer(position.toString(), 9, 6)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (existingBlock.eq(KryptonBlocks.HOPPER)) {
            // A hopper pushes items into the container directly below it; record the target at open time.
            val below = Vec3i(position.x, position.y - 1, position.z)
            val belowBlock = chunk.getBlock(below)
            val belowSize = when {
                belowBlock.eq(KryptonBlocks.CHEST) || belowBlock.eq(KryptonBlocks.BARREL) -> 27
                belowBlock.eq(KryptonBlocks.DISPENSER) || belowBlock.eq(KryptonBlocks.DROPPER) -> 9
                belowBlock.eq(KryptonBlocks.HOPPER) -> 5
                else -> 0
            }
            if (belowSize > 0) KryptonChestInventory.registerHopper(position.toString(), below.toString(), belowSize)
            ensureContainerTick()
            openContainer(position.toString(), 5, 15) // 15 = hopper menu type
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (existingBlock.eq(KryptonBlocks.CHEST)) {
            // A chest adjacent (N/S/E/W) to another chest forms a double chest (54 slots, generic_9x6).
            val east = Vec3i(position.x + 1, position.y, position.z)
            val west = Vec3i(position.x - 1, position.y, position.z)
            val south = Vec3i(position.x, position.y, position.z + 1)
            val north = Vec3i(position.x, position.y, position.z - 1)
            val neighbor = when {
                chunk.getBlock(east).eq(KryptonBlocks.CHEST) -> east
                chunk.getBlock(west).eq(KryptonBlocks.CHEST) -> west
                chunk.getBlock(south).eq(KryptonBlocks.CHEST) -> south
                chunk.getBlock(north).eq(KryptonBlocks.CHEST) -> north
                else -> null
            }
            if (neighbor != null) {
                openContainer("D:" + minOf(position.toString(), neighbor.toString()), 54, 5)
            } else {
                openContainer(position.toString(), 27, 2)
            }
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (existingBlock.eq(KryptonBlocks.BARREL)) {
            openContainer(position.toString(), 27, 2) // generic_9x3
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (existingBlock.eq(KryptonBlocks.DISPENSER) || existingBlock.eq(KryptonBlocks.DROPPER)) {
            openContainer(position.toString(), 9, 6) // generic_3x3
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Redstone: right-clicking a lever toggles it; right-clicking a button presses it (timed impulse).
        // Both light/extinguish directly-adjacent redstone lamps.
        if (existingBlock.eq(KryptonBlocks.LEVER)) {
            toggleLever(chunk, position, existingBlock)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (keyValue(existingBlock).endsWith("_button")) {
            pressButton(chunk, position, existingBlock)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Right-clicking a note block cycles its pitch (note 0..24, wrapping) and plays the tuned harp note.
        if (existingBlock.eq(KryptonBlocks.NOTE_BLOCK)) {
            cycleNoteBlock(chunk, position, existingBlock)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Right-clicking a (non-iron) door, trapdoor or fence gate opens/closes it by hand.
        if (toggleOpenableByHand(chunk, position, existingBlock)) {
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Right-clicking a bed at night sleeps through to the next morning, and sets the player's respawn point there.
        if (keyValue(existingBlock).endsWith("_bed")) {
            trySleep(position)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Bone meal on a growing crop (anything with the 0..7 age property) jumps it straight to full growth.
        if (player.inventory.mainHand.type.key().value() == "bone_meal" && existingBlock.hasProperty(KryptonProperties.AGE_7)) {
            val grown = existingBlock.setProperty(KryptonProperties.AGE_7, MAX_CROP_AGE)
            chunk.setBlock(position, grown, false)
            broadcastBlockUpdate(position, grown)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Bone meal on a grass block scatters short grass (and the odd flower) on the air above nearby grass blocks.
        if (player.inventory.mainHand.type.key().value() == "bone_meal" && existingBlock.eq(KryptonBlocks.GRASS_BLOCK)) {
            val w = player.world
            for (dx in -1..1) for (dz in -1..1) {
                val ground = Vec3i(position.x + dx, position.y, position.z + dz)
                val above = Vec3i(ground.x, ground.y + 1, ground.z)
                if (!wBlock(w, ground).eq(KryptonBlocks.GRASS_BLOCK) || !wBlock(w, above).eq(KryptonBlocks.AIR)) continue
                val plant = when { // centre is always short grass (deterministic); two corners get flowers for variety
                    dx == 0 && dz == 0 -> KryptonBlocks.GRASS
                    dx == -1 && dz == -1 -> KryptonBlocks.POPPY
                    dx == 1 && dz == 1 -> KryptonBlocks.DANDELION
                    else -> KryptonBlocks.GRASS
                }.defaultState
                wSet(w, above, plant)
                broadcastBlockUpdate(above, plant)
            }
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Composting: right-clicking a composter with a compostable item raises its fill level (0..8) and plays the
        // fill effect; when full (level 8), right-clicking harvests bone meal and empties it back to 0.
        if (existingBlock.eq(KryptonBlocks.COMPOSTER)) {
            val level = existingBlock.requireProperty(KryptonProperties.COMPOSTER_LEVEL)
            if (level >= COMPOSTER_FULL) {
                val emptied = existingBlock.setProperty(KryptonProperties.COMPOSTER_LEVEL, 0)
                chunk.setBlock(position, emptied, false)
                broadcastBlockUpdate(position, emptied)
                player.inventory.setHeldItem(Hand.MAIN, KryptonItemStack(KryptonRegistries.ITEM.get(Key.key("bone_meal"))))
                player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
                return
            }
            if (player.inventory.mainHand.type.key().value() in COMPOSTABLES) {
                val filled = existingBlock.setProperty(KryptonProperties.COMPOSTER_LEVEL, level + 1)
                chunk.setBlock(position, filled, false)
                broadcastBlockUpdate(position, filled)
                chunk.world.worldEvent(position, org.kryptonmc.krypton.world.WorldEvents.COMPOSTER_FILL, 0, null)
                if (player.gameMode != GameMode.CREATIVE) {
                    val held = player.inventory.mainHand
                    player.inventory.setHeldItem(Hand.MAIN, if (held.amount <= 1) KryptonItemStack.EMPTY else held.shrink(1))
                }
                player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
                return
            }
        }
        // Cauldron: a water bucket fills an empty cauldron (-> full water cauldron + empty bucket).
        if (existingBlock.eq(KryptonBlocks.CAULDRON) && player.inventory.mainHand.type.key().value() == "water_bucket") {
            val filled = KryptonBlocks.WATER_CAULDRON.defaultState.setProperty(KryptonProperties.CAULDRON_LEVEL, 3)
            chunk.setBlock(position, filled, false)
            broadcastBlockUpdate(position, filled)
            player.inventory.setHeldItem(Hand.MAIN, KryptonItemStack(KryptonRegistries.ITEM.get(Key.key("bucket"))))
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // ...and an empty bucket empties a water cauldron (-> empty cauldron + water bucket).
        if (existingBlock.eq(KryptonBlocks.WATER_CAULDRON) && player.inventory.mainHand.type.key().value() == "bucket") {
            val emptied = KryptonBlocks.CAULDRON.defaultState
            chunk.setBlock(position, emptied, false)
            broadcastBlockUpdate(position, emptied)
            player.inventory.setHeldItem(Hand.MAIN, KryptonItemStack(KryptonRegistries.ITEM.get(Key.key("water_bucket"))))
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Filling a bucket: an empty bucket on water removes the water (source + its connected flow) and yields a water bucket.
        if (player.inventory.mainHand.type.key().value() == "bucket" && existingBlock.eq(KryptonBlocks.WATER)) {
            removeWater(player.world, position)
            player.inventory.setHeldItem(Hand.MAIN, KryptonItemStack(KryptonRegistries.ITEM.get(Key.key("water_bucket"))))
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Flint and steel on a TNT block lights it: the block becomes a falling primed-TNT entity that explodes after
        // its fuse (clearing nearby blocks in a sphere — see KryptonPrimedTnt).
        if (player.inventory.mainHand.type.key().value() == "flint_and_steel" && existingBlock.eq(KryptonBlocks.TNT)) {
            val w = player.world
            val air = KryptonBlocks.AIR.defaultState
            wSet(w, position, air); broadcastBlockUpdate(position, air)
            val tnt = KryptonPrimedTnt(w)
            tnt.position = player.position.withCoordinates(position.x + 0.5, position.y.toDouble(), position.z + 0.5)
            w.spawnEntity(tnt)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Right-clicking an enchanting table enchants the held item (adds an "Enchantments" NBT tag). v2: it now costs
        // one lapis lazuli — found anywhere in the inventory and consumed; with no lapis, nothing happens. Simplified:
        // always Sharpness I, with no XP cost and no enchant-options screen.
        if (existingBlock.eq(KryptonBlocks.ENCHANTING_TABLE)) {
            val held = player.inventory.mainHand
            val lapisSlot = (0 until PLAYER_INVENTORY_SIZE).firstOrNull {
                val item = player.inventory.getItem(it)
                item.type.key().value() == "lapis_lazuli" && item.amount > 0
            }
            if (held !== KryptonItemStack.EMPTY && held.amount > 0 && lapisSlot != null) {
                val lapis = player.inventory.getItem(lapisSlot)
                player.inventory.setItem(lapisSlot, if (lapis.amount <= 1) KryptonItemStack.EMPTY else lapis.shrink(1)) // pay one lapis
                val enchantments = MutableListTag.of(ArrayList(), EndTag.ID)
                enchantments.add(ImmutableCompoundTag.builder().putString("id", "minecraft:sharpness").putShort("lvl", 1.toShort()).build())
                val data = ImmutableCompoundTag.builder().put("Enchantments", enchantments).build()
                player.inventory.setHeldItem(Hand.MAIN, held.withMeta(KryptonItemMeta(data))) // setter sends the slot update
            }
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        // Right-clicking a comparator cycles its mode (compare <-> subtract), then re-reads its inputs.
        if (existingBlock.eq(KryptonBlocks.COMPARATOR)) {
            val mode = existingBlock.requireProperty(KryptonProperties.COMPARATOR_MODE)
            val next = if (mode == ComparatorMode.SUBTRACT) ComparatorMode.COMPARE else ComparatorMode.SUBTRACT
            chunk.setBlock(position, existingBlock.setProperty(KryptonProperties.COMPARATOR_MODE, next), false)
            broadcastBlockUpdate(position, chunk.getBlock(position))
            evaluateComparator(player.world, position)
            player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            return
        }
        if (!player.canBuild) return // If they can't place blocks, they are irrelevant :)

        val state = world.getBlock(position)
        val face = packet.hitResult.direction
        val isInside = packet.hitResult.isInside
        val event = server.eventNode.fire(KryptonPlayerPlaceBlockEvent(player, state, packet.hand, position, face, isInside))
        if (!event.isAllowed()) return

        if (!existingBlock.eq(KryptonBlocks.AIR)) return
        // Most items share their block's key; a few don't (e.g. the "redstone" item places the "redstone_wire" block).
        val itemKey = player.inventory.mainHand.type.key().value()
        // Openables (doors/trapdoors/fence gates) must be placed CLOSED — some defaultStates carry OPEN=true. setProperty
        // is a no-op for blocks lacking the OPEN property (see KryptonState.setProperty), so this is safe for everything.
        val baseState = KryptonRegistries.BLOCK.get(Key.key(ITEM_BLOCK_OVERRIDES.getOrDefault(itemKey, itemKey))).defaultState
            .setProperty(KryptonProperties.OPEN, false)
        val isDoor = keyValue(baseState).endsWith("_door")
        // A door is two blocks tall: force the clicked block to the LOWER half (its defaultState's half is unreliable).
        val newState = if (isDoor) baseState.setProperty(KryptonProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER) else baseState
        chunk.setBlock(position, newState, false)
        // Notify the client of the placed block and acknowledge the action sequence (both were missing).
        player.connection.send(PacketOutBlockUpdate(position, newState))
        if (newState.eq(KryptonBlocks.WATER)) spreadWater(world, position) // a placed water source flows out (falls + spreads)
        world.playSound(position, newState.block.soundGroup.placeSound, net.kyori.adventure.sound.Sound.Source.BLOCK, 1F, 1F, player) // block-place sound
        // Place the door's matching upper half directly above the lower half.
        if (isDoor) {
            val upperPos = Vec3i(position.x, position.y + 1, position.z)
            val upperState = baseState.setProperty(KryptonProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
            chunk.setBlock(upperPos, upperState, false)
            player.connection.send(PacketOutBlockUpdate(upperPos, upperState))
        }
        // A comparator continuously reflects the container behind it — register it and do an initial read.
        if (newState.eq(KryptonBlocks.COMPARATOR)) {
            comparators.add(position)
            evaluateComparator(player.world, position)
        }
        // A planted crop (wheat/carrots/potatoes) grows over time on the shared tick.
        if (CropManager.register(world, position, newState)) ensureContainerTick()
        player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
    }

    private var currentPlate: Vec3i? = null // the pressure plate this player is currently standing on, if any

    /** Toggles a lever's `powered` state and lights/extinguishes redstone lamps directly adjacent to it. */
    private fun toggleLever(chunk: KryptonChunk, position: Vec3i, lever: KryptonBlockState) {
        val powered = !lever.requireProperty(KryptonProperties.POWERED)
        chunk.setBlock(position, lever.setProperty(KryptonProperties.POWERED, powered), false)
        broadcastBlockUpdate(position, chunk.getBlock(position))
        chunk.world.playSound(position, org.kryptonmc.api.effect.sound.SoundEvents.LEVER_CLICK.get(), net.kyori.adventure.sound.Sound.Source.BLOCK, 0.3F, if (powered) 0.6F else 0.5F, null) // lever click sound
        powerAdjacentConsumers(chunk, position, powered)
        recomputeRedstone(chunk, position)
    }

    /** Presses a button: powers it + adjacent lamps, then auto-resets after [BUTTON_PRESS_TICKS] (impulse). */
    private fun pressButton(chunk: KryptonChunk, position: Vec3i, button: KryptonBlockState) {
        // Always power on press + light lamps (don't early-return on POWERED — some buttons default to powered).
        chunk.setBlock(position, button.setProperty(KryptonProperties.POWERED, true), false)
        broadcastBlockUpdate(position, chunk.getBlock(position))
        chunk.world.playSound(position, org.kryptonmc.api.effect.sound.SoundEvents.WOODEN_BUTTON_CLICK_ON.get(), net.kyori.adventure.sound.Sound.Source.BLOCK, 0.3F, 0.6F, null) // button click-on
        powerAdjacentConsumers(chunk, position, true)
        recomputeRedstone(chunk, position)
        server.scheduler.buildTask {
            val current = chunk.getBlock(position)
            if (keyValue(current).endsWith("_button") && current.requireProperty(KryptonProperties.POWERED)) {
                chunk.setBlock(position, current.setProperty(KryptonProperties.POWERED, false), false)
                broadcastBlockUpdate(position, chunk.getBlock(position))
                chunk.world.playSound(position, org.kryptonmc.api.effect.sound.SoundEvents.WOODEN_BUTTON_CLICK_OFF.get(), net.kyori.adventure.sound.Sound.Source.BLOCK, 0.3F, 0.5F, null) // button click-off (auto-reset)
                powerAdjacentConsumers(chunk, position, false)
                recomputeRedstone(chunk, position)
            }
        }.delay(TaskTime.ticks(BUTTON_PRESS_TICKS)).executionType(ExecutionType.SYNCHRONOUS).schedule()
    }

    /** Cycles a note block's pitch (note 0..24, wrapping) and plays the tuned harp note at the matching pitch. */
    private fun cycleNoteBlock(chunk: KryptonChunk, position: Vec3i, noteBlock: KryptonBlockState) {
        val note = (noteBlock.requireProperty(KryptonProperties.NOTE) + 1) % 25
        chunk.setBlock(position, noteBlock.setProperty(KryptonProperties.NOTE, note), false)
        broadcastBlockUpdate(position, chunk.getBlock(position))
        val pitch = Math.pow(2.0, (note - 12) / 12.0).toFloat() // vanilla tuning: note 0 -> 0.5, note 12 -> 1.0, note 24 -> 2.0
        chunk.world.playSound(position, org.kryptonmc.api.effect.sound.SoundEvents.NOTE_BLOCK_HARP.get(), net.kyori.adventure.sound.Sound.Source.RECORD, 3F, pitch, null) // tuned note
    }

    /**
     * Right-clicking a (non-iron) door, trapdoor or fence gate toggles its OPEN state by hand — syncing a door's two
     * halves and playing the matching open/close effect. Iron doors/trapdoors only react to redstone, so they're skipped.
     * Returns true if [state] was a hand-openable block (so the caller stops dispatch and acknowledges).
     */
    private fun toggleOpenableByHand(chunk: KryptonChunk, position: Vec3i, state: KryptonBlockState): Boolean {
        val key = keyValue(state)
        val isTrapdoor = key.endsWith("_trapdoor")
        val isDoor = key.endsWith("_door")
        val isGate = key.endsWith("_fence_gate")
        if (!isTrapdoor && !isDoor && !isGate) return false
        if (key == "iron_door" || key == "iron_trapdoor") return false // iron only opens via redstone, never by hand
        val open = !state.requireProperty(KryptonProperties.OPEN)
        setBoolProperty(chunk, position, state, KryptonProperties.OPEN, open)
        when {
            isTrapdoor -> chunk.world.worldEvent(position, if (open) org.kryptonmc.krypton.world.WorldEvents.OPEN_WOODEN_TRAP_DOOR else org.kryptonmc.krypton.world.WorldEvents.CLOSE_WOODEN_TRAP_DOOR, 0, null)
            isDoor -> {
                // A door is two blocks tall — keep the other half's OPEN in sync so it renders/behaves as one door.
                val otherY = if (state.requireProperty(KryptonProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) position.y + 1 else position.y - 1
                val otherPos = Vec3i(position.x, otherY, position.z)
                val other = wBlock(chunk.world, otherPos)
                if (keyValue(other).endsWith("_door")) setBoolProperty(chunk, otherPos, other, KryptonProperties.OPEN, open)
                chunk.world.worldEvent(position, if (open) org.kryptonmc.krypton.world.WorldEvents.OPEN_WOODEN_DOOR else org.kryptonmc.krypton.world.WorldEvents.CLOSE_WOODEN_DOOR, 0, null)
            }
            isGate -> chunk.world.playSound(position, if (open) org.kryptonmc.api.effect.sound.SoundEvents.FENCE_GATE_OPEN.get() else org.kryptonmc.api.effect.sound.SoundEvents.FENCE_GATE_CLOSE.get(), net.kyori.adventure.sound.Sound.Source.BLOCK, 1F, 1F, null)
        }
        return true
    }

    /**
     * Sleeping: right-clicking a bed at night (or always, with -Dkrypton.allowSleepAnytime) skips the world clock to the
     * next morning and pushes the new time to clients immediately. Simplified: no sleeping pose/animation, no
     * all-players-must-sleep requirement, and the spawn point is not yet moved to the bed.
     */
    private fun trySleep(bedPos: Vec3i) {
        val data = player.world.data
        val timeOfDay = data.dayTime % DAY_LENGTH
        if (!SLEEP_ANYTIME && timeOfDay !in NIGHT_START..NIGHT_END) return // beds only work at night
        // Successfully using a bed sets the player's respawn point to it (used by respawnPlayer after death).
        player.respawnData = RespawnData(bedPos, player.world.dimension, player.position.yaw, false)
        data.dayTime = (data.dayTime / DAY_LENGTH + 1L) * DAY_LENGTH // jump to dawn of the next day
        PacketGrouping.sendGroupedPacket(server, PacketOutUpdateTime.create(data)) { it.world === player.world }
        // Lay the player down (POSE = SLEEPING, broadcast to nearby players via postTick) then wake them after a moment.
        player.data.set(MetadataKeys.Entity.POSE, Pose.SLEEPING)
        server.scheduler.buildTask { player.data.set(MetadataKeys.Entity.POSE, Pose.STANDING) }
            .delay(TaskTime.ticks(SLEEP_POSE_TICKS)).executionType(ExecutionType.SYNCHRONOUS).schedule()
    }

    /** Updates the pressure plate the player is standing on (power on enter, off on leave), with adjacent lamps. */
    private fun updatePressurePlate(position: Position) {
        val feet = Vec3i(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
        val onPlate = keyValue(player.world.getBlock(feet.x, feet.y, feet.z)).endsWith("_pressure_plate")
        val newPlate = if (onPlate) feet else null
        if (newPlate == currentPlate) return
        currentPlate?.let { setPlatePowered(it, false) } // stepped off the previous plate
        newPlate?.let { setPlatePowered(it, true) }      // stepped onto a new plate
        currentPlate = newPlate
    }

    private fun setPlatePowered(position: Vec3i, powered: Boolean) {
        val chunk = player.world.chunkManager.getChunk(ChunkPos.forEntityPosition(player.position)) ?: return
        val plate = chunk.getBlock(position)
        if (!keyValue(plate).endsWith("_pressure_plate")) return
        chunk.setBlock(position, plate.setProperty(KryptonProperties.POWERED, powered), false)
        broadcastBlockUpdate(position, chunk.getBlock(position))
        powerAdjacentConsumers(chunk, position, powered)
        recomputeRedstone(chunk, position)
    }

    /** Direct-adjacency power: switches redstone consumers (lamps, trapdoors) next to [position]. */
    private fun powerAdjacentConsumers(chunk: KryptonChunk, position: Vec3i, powered: Boolean) {
        for (offset in NEIGHBOUR_OFFSETS) {
            applyConsumer(chunk, Vec3i(position.x + offset.x, position.y + offset.y, position.z + offset.z), powered)
        }
    }

    /** True if [state] is a redstone consumer that reacts to power (lamp, trapdoor, door, piston, dispenser/dropper). */
    private fun isConsumer(state: KryptonBlockState): Boolean {
        val key = keyValue(state)
        return state.eq(KryptonBlocks.REDSTONE_LAMP) || key.endsWith("_trapdoor") || key.endsWith("_door") ||
            key == "piston" || key == "sticky_piston" || key == "dispenser" || key == "dropper"
    }

    /** Applies a consumer's on/off state (lamp→LIT, trapdoor/door→OPEN, piston→EXTENDED+head), broadcasting the change. Returns true if [pos] held a consumer. */
    private fun applyConsumer(chunk: KryptonChunk, pos: Vec3i, powered: Boolean): Boolean {
        val state = wBlock(chunk.world, pos) // [pos] may be in a different chunk than the wire that drove this consumer
        val key = keyValue(state)
        when {
            state.eq(KryptonBlocks.REDSTONE_LAMP) -> setBoolProperty(chunk, pos, state, KryptonProperties.LIT, powered)
            key.endsWith("_trapdoor") -> {
                val wasOpen = state.requireProperty(KryptonProperties.OPEN)
                setBoolProperty(chunk, pos, state, KryptonProperties.OPEN, powered)
                // Play the trapdoor open/close sound (only on an actual change — recompute calls this twice per toggle).
                if (wasOpen != powered) chunk.world.worldEvent(pos, if (powered) org.kryptonmc.krypton.world.WorldEvents.OPEN_WOODEN_TRAP_DOOR else org.kryptonmc.krypton.world.WorldEvents.CLOSE_WOODEN_TRAP_DOOR, 0, null)
            }
            key.endsWith("_door") -> {
                val wasOpen = state.requireProperty(KryptonProperties.OPEN)
                setBoolProperty(chunk, pos, state, KryptonProperties.OPEN, powered)
                // A door is two blocks tall — keep the other half's OPEN in sync so it renders/behaves as one door.
                val otherY = if (state.requireProperty(KryptonProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) pos.y + 1 else pos.y - 1
                val otherPos = Vec3i(pos.x, otherY, pos.z)
                val other = wBlock(chunk.world, otherPos)
                if (keyValue(other).endsWith("_door")) setBoolProperty(chunk, otherPos, other, KryptonProperties.OPEN, powered)
                if (wasOpen != powered) chunk.world.worldEvent(pos, if (powered) org.kryptonmc.krypton.world.WorldEvents.OPEN_WOODEN_DOOR else org.kryptonmc.krypton.world.WorldEvents.CLOSE_WOODEN_DOOR, 0, null)
            }
            key == "piston" || key == "sticky_piston" -> setPistonExtended(chunk, pos, state, powered)
            key == "dispenser" || key == "dropper" -> dispenseOnPower(chunk, pos, state, powered)
            else -> return false
        }
        return true
    }

    /** On the rising edge of power, a dispenser/dropper ejects one item from its contents as a dropped-item entity. */
    private fun dispenseOnPower(chunk: KryptonChunk, pos: Vec3i, state: KryptonBlockState, powered: Boolean) {
        if (!powered) { poweredDispensers.remove(pos); return }
        if (!poweredDispensers.add(pos)) return // already powered — fire only on the rising edge (we're called twice per toggle)
        val item = KryptonChestInventory.dispenseOne(pos.toString()) ?: return // empty — nothing to dispense
        chunk.world.worldEvent(pos, org.kryptonmc.krypton.world.WorldEvents.DISPENSER_DISPENSES, 0, null) // dispense "click" sound
        val facing = state.requireProperty(KryptonProperties.FACING)
        val drop = KryptonItemEntity(chunk.world)
        drop.position = Position(
            pos.x + 0.5 + facing.normalX * 0.7, pos.y + 0.5 + facing.normalY * 0.7, pos.z + 0.5 + facing.normalZ * 0.7, 0F, 0F
        )
        drop.item = item
        chunk.world.spawnEntity(drop)
        ItemDropManager.add(drop)
        ensureContainerTick() // the shared tick drives ItemDropManager (pickup/despawn)
    }

    /**
     * Extends/retracts a piston: toggles EXTENDED and places/removes the piston_head one block in the piston's
     * FACING direction. On extend it pushes the whole column of consecutive movable blocks in front (up to
     * [MAX_PISTON_PUSH], like vanilla) one block along FACING. If the column hits an immovable block or is longer
     * than the limit (no air to slide into), the piston stays retracted. Blocks are moved per-position (`wBlock`/`wSet`),
     * so a push may span chunk boundaries.
     */
    private fun setPistonExtended(chunk: KryptonChunk, pos: Vec3i, state: KryptonBlockState, extended: Boolean) {
        if (state.requireProperty(KryptonProperties.EXTENDED) == extended) return
        val world = chunk.world
        val facing = state.requireProperty(KryptonProperties.FACING)
        val headPos = Vec3i(pos.x + facing.normalX, pos.y + facing.normalY, pos.z + facing.normalZ)
        if (extended) {
            // Collect the run of movable blocks the head would shove, starting at headPos along FACING, until air.
            val column = ArrayList<Vec3i>()
            var cur = headPos
            while (true) {
                val b = wBlock(world, cur)
                if (keyValue(b) == "air") break       // found the empty space the column slides into
                if (!isPushable(b)) return            // an immovable block blocks the piston — it can't extend (vanilla)
                column.add(cur)
                if (column.size > MAX_PISTON_PUSH) return // longer than the push limit — can't extend
                cur = Vec3i(cur.x + facing.normalX, cur.y + facing.normalY, cur.z + facing.normalZ)
            }
            // Shift every block one along FACING, FAR END FIRST so a block is read before its old slot is overwritten.
            for (i in column.indices.reversed()) {
                val from = column[i]
                val to = Vec3i(from.x + facing.normalX, from.y + facing.normalY, from.z + facing.normalZ)
                val moved = wBlock(world, from)
                wSet(world, to, moved)
                broadcastBlockUpdate(to, moved)
            }
            setBoolProperty(chunk, pos, state, KryptonProperties.EXTENDED, true)
            world.playSound(pos, org.kryptonmc.api.effect.sound.SoundEvents.PISTON_EXTEND.get(), net.kyori.adventure.sound.Sound.Source.BLOCK, 0.5F, 0.7F, null) // piston extend sound
            val head = KryptonBlocks.PISTON_HEAD.defaultState.setProperty(KryptonProperties.FACING, facing)
            wSet(world, headPos, head) // fills the vacated headPos (column[0] moved forward, or it was air)
            broadcastBlockUpdate(headPos, head)
        } else {
            setBoolProperty(chunk, pos, state, KryptonProperties.EXTENDED, false)
            world.playSound(pos, org.kryptonmc.api.effect.sound.SoundEvents.PISTON_CONTRACT.get(), net.kyori.adventure.sound.Sound.Source.BLOCK, 0.5F, 0.7F, null) // piston retract sound
            if (keyValue(wBlock(world, headPos)) == "piston_head") {
                // Only clear our own head, never an arbitrary block that happens to be in front.
                wSet(world, headPos, KryptonBlocks.AIR.defaultState)
                broadcastBlockUpdate(headPos, KryptonBlocks.AIR.defaultState)
            }
            // A sticky piston pulls the single block that was in front of the head (now at P+2F) back into the head spot.
            if (keyValue(state) == "sticky_piston") {
                val pullFrom = Vec3i(pos.x + 2 * facing.normalX, pos.y + 2 * facing.normalY, pos.z + 2 * facing.normalZ)
                val pulled = wBlock(world, pullFrom)
                if (isPushable(pulled) && keyValue(wBlock(world, headPos)) == "air") {
                    wSet(world, headPos, pulled)
                    broadcastBlockUpdate(headPos, pulled)
                    wSet(world, pullFrom, KryptonBlocks.AIR.defaultState)
                    broadcastBlockUpdate(pullFrom, KryptonBlocks.AIR.defaultState)
                }
            }
        }
    }

    /** True if a piston may push [state]: a solid, non-air block that isn't itself piston machinery or an immovable block. */
    private fun isPushable(state: KryptonBlockState): Boolean {
        val key = keyValue(state)
        return state.isSolid() && key != "piston_head" && key != "piston" && key != "sticky_piston" &&
            key != "moving_piston" && key != "obsidian" && key != "bedrock"
    }

    /** Sets a boolean blockstate property at [pos] (if it differs) and broadcasts the change. Resolves [pos]'s own chunk (may differ from the wire's). */
    private fun setBoolProperty(chunk: KryptonChunk, pos: Vec3i, state: KryptonBlockState, property: KryptonProperty<Boolean>, value: Boolean) {
        if (state.requireProperty(property) != value) {
            wSet(chunk.world, pos, state.setProperty(property, value))
            broadcastBlockUpdate(pos, wBlock(chunk.world, pos))
        }
    }

    /** Reads the block at [pos] via the world, which resolves the owning chunk per-position — so redstone can cross chunk boundaries. */
    private fun wBlock(world: KryptonWorld, pos: Vec3i): KryptonBlockState = world.getBlock(pos.x, pos.y, pos.z)

    /** Writes [state] at [pos] into its owning chunk (no-op if that chunk isn't loaded). Mirrors the old `chunk.setBlock(pos, state, false)`. */
    private fun wSet(world: KryptonWorld, pos: Vec3i, state: KryptonBlockState) {
        world.chunkManager.getChunk(ChunkPos(pos.x shr 4, pos.z shr 4))?.setBlock(pos, state, false)
    }

    /**
     * Returns water with its block `level` set so the client renders flowing (shallower with distance) or falling water.
     * Safe by construction: [setProperty] silently returns the unchanged state if the block lacks that property
     * (see [KryptonState.setProperty]), so an unsupported water model just falls back to flat source water — no regression.
     */
    private fun waterLevel(source: KryptonBlockState, level: Int): KryptonBlockState {
        if (level <= 0) return source // level 0 == full source block
        val byLevel = source.setProperty(KryptonProperties.LEVEL, level.coerceIn(0, 15))
        if (byLevel !== source) return byLevel // vanilla LiquidBlock exposes the 0..15 "level" property
        return source.setProperty(KryptonProperties.LIQUID_LEVEL, level.coerceIn(1, 8)) // fluid-style 1..8, else no-op
    }

    /**
     * A placed water source flows outward (synchronous, like the redstone recompute): it falls straight down into air,
     * and over solid ground it spreads horizontally up to [WATER_FLOW_RANGE] blocks. Each placed water block is written
     * per-position (so the flow may cross chunk borders) and broadcast to clients. The flow is level-decremented so it
     * renders shallower with distance (ring N -> level N) and falling where it drops ([WATER_FALLING_LEVEL]); the source
     * itself stays level 0 (a full block). Bounded by [WATER_FLOW_MAX_BLOCKS] as a safety cap.
     */
    private fun spreadWater(world: KryptonWorld, source: Vec3i) {
        val water = KryptonBlocks.WATER.defaultState
        val queue = ArrayDeque<Pair<Vec3i, Int>>()
        val visited = HashSet<Long>()
        queue.add(source to 0)
        visited.add(packPos(source.x, source.y, source.z))
        var placed = 0
        while (queue.isNotEmpty() && placed < WATER_FLOW_MAX_BLOCKS) {
            val (pos, dist) = queue.removeFirst()
            val below = Vec3i(pos.x, pos.y - 1, pos.z)
            if (wBlock(world, below).eq(KryptonBlocks.AIR)) {
                if (visited.add(packPos(below.x, below.y, below.z))) {
                    val fall = waterLevel(water, WATER_FALLING_LEVEL) // falling water renders as a full column
                    wSet(world, below, fall); broadcastBlockUpdate(below, fall); placed++
                    queue.add(below to dist) // falling doesn't consume horizontal range
                }
            } else if (dist < WATER_FLOW_RANGE) {
                for (d in arrayOf(Vec3i(1, 0, 0), Vec3i(-1, 0, 0), Vec3i(0, 0, 1), Vec3i(0, 0, -1))) {
                    val n = Vec3i(pos.x + d.x, pos.y, pos.z + d.z)
                    if (!visited.add(packPos(n.x, n.y, n.z))) continue
                    if (!wBlock(world, n).eq(KryptonBlocks.AIR)) continue
                    val ground = !wBlock(world, Vec3i(n.x, n.y - 1, n.z)).eq(KryptonBlocks.AIR)
                    val flow = waterLevel(water, dist + 1) // one ring further from the source -> one level shallower
                    wSet(world, n, flow); broadcastBlockUpdate(n, flow); placed++
                    queue.add(n to (if (ground) dist + 1 else dist)) // flow flat over ground; fall off a ledge
                }
            }
        }
    }

    /**
     * Removes a body of water reachable from [start] (the inverse of [spreadWater]): a flood-fill over the 6 face
     * neighbours sets every connected WATER block to air and broadcasts it, so scooping a placed source also clears the
     * flow it produced. Bounded by [WATER_FLOW_MAX_BLOCKS]. A no-op if [start] isn't water.
     */
    private fun removeWater(world: KryptonWorld, start: Vec3i) {
        if (!wBlock(world, start).eq(KryptonBlocks.WATER)) return
        val air = KryptonBlocks.AIR.defaultState
        val queue = ArrayDeque<Vec3i>()
        val visited = HashSet<Long>()
        queue.add(start); visited.add(packPos(start.x, start.y, start.z))
        var removed = 0
        while (queue.isNotEmpty() && removed < WATER_FLOW_MAX_BLOCKS) {
            val pos = queue.removeFirst()
            if (!wBlock(world, pos).eq(KryptonBlocks.WATER)) continue
            wSet(world, pos, air); broadcastBlockUpdate(pos, air); removed++
            for (o in NEIGHBOUR_OFFSETS) {
                val n = Vec3i(pos.x + o.x, pos.y + o.y, pos.z + o.z)
                if (visited.add(packPos(n.x, n.y, n.z))) queue.add(n)
            }
        }
    }

    private fun packPos(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFFL shl 38) or (z.toLong() and 0x3FFFFFFL shl 12) or (y.toLong() and 0xFFFL)

    /**
     * Recomputes the redstone-wire network connected to [sourcePos]: wires adjacent to a powered source get
     * signal strength 15, propagated outward decreasing by 1 per wire (BFS), and lamps next to any powered wire
     * (or powered source) light up. Blocks are read/written per-position via the world (`wBlock`/`wSet`), so the
     * network may span chunk boundaries — the [chunk] argument is just a handle to the world.
     */
    private fun recomputeRedstone(chunk: KryptonChunk, sourcePos: Vec3i) {
        val world = chunk.world
        // Repeaters next to the changed source re-evaluate their (delayed) output, independent of any wire network.
        updateRepeaters(chunk, sourcePos)
        // Comparators read redstone inputs too, so a source change must refresh them — unless this recompute was itself
        // triggered by a comparator propagating into its front wire (inComparatorEval), in which case re-evaluating
        // would recurse forever. The comparator that triggered us already set its POWERED state before calling in here.
        if (!inComparatorEval.get()) reevaluateComparators(world)
        // Flood-fill all connected redstone wires, starting from those around the source (crossing chunk boundaries).
        val wires = LinkedHashSet<Vec3i>()
        val stack = ArrayDeque<Vec3i>()
        fun considerWire(p: Vec3i) { if (wBlock(world, p).eq(KryptonBlocks.REDSTONE_WIRE) && wires.add(p)) stack.addLast(p) }
        for (o in NEIGHBOUR_OFFSETS) considerWire(Vec3i(sourcePos.x + o.x, sourcePos.y + o.y, sourcePos.z + o.z))
        while (stack.isNotEmpty()) {
            val w = stack.removeLast()
            for (o in NEIGHBOUR_OFFSETS) considerWire(Vec3i(w.x + o.x, w.y + o.y, w.z + o.z))
        }
        if (wires.isEmpty()) return

        // Power: wire next to a source = its strength; relax outward (BFS) -1/block.
        // Sources: lever/button/plate & FRONT of a powered repeater = 15; FRONT of a powered comparator = its analog output (0-15).
        val power = HashMap<Vec3i, Int>()
        val queue = ArrayDeque<Vec3i>()
        for (w in wires) {
            var seed = 0
            for (o in NEIGHBOUR_OFFSETS) {
                val n = Vec3i(w.x + o.x, w.y + o.y, w.z + o.z)
                val nb = wBlock(world, n)
                val s = when {
                    isPoweredSource(nb) -> 15
                    nb.eq(KryptonBlocks.REPEATER) && nb.requireProperty(KryptonProperties.POWERED) && repeaterFront(nb, n) == w -> 15
                    nb.eq(KryptonBlocks.COMPARATOR) && nb.requireProperty(KryptonProperties.POWERED) && repeaterFront(nb, n) == w -> comparatorOutput(world, n)
                    else -> 0
                }
                if (s > seed) seed = s
            }
            power[w] = seed
            if (seed > 0) queue.addLast(w)
        }
        while (queue.isNotEmpty()) {
            val w = queue.removeFirst()
            val next = power.getValue(w) - 1
            for (o in NEIGHBOUR_OFFSETS) {
                val n = Vec3i(w.x + o.x, w.y + o.y, w.z + o.z)
                if (n in wires && power.getValue(n) < next) {
                    power[n] = next
                    if (next > 1) queue.addLast(n)
                }
            }
        }

        // Apply wire power + collect adjacent consumers (lamps, trapdoors).
        val consumers = LinkedHashSet<Vec3i>()
        for (w in wires) {
            val cur = wBlock(world, w)
            if (cur.requireProperty(KryptonProperties.POWER) != power.getValue(w)) {
                wSet(world, w, cur.setProperty(KryptonProperties.POWER, power.getValue(w)))
                broadcastBlockUpdate(w, wBlock(world, w))
            }
            for (o in NEIGHBOUR_OFFSETS) {
                val n = Vec3i(w.x + o.x, w.y + o.y, w.z + o.z)
                if (isConsumer(wBlock(world, n))) consumers.add(n)
            }
        }
        // A consumer switches on if any adjacent wire carries power, or it touches a powered source directly.
        for (c in consumers) {
            val on = NEIGHBOUR_OFFSETS.any {
                val n = Vec3i(c.x + it.x, c.y + it.y, c.z + it.z)
                (power[n]?.let { p -> p > 0 } ?: false) || isPoweredSource(wBlock(world, n))
            }
            applyConsumer(chunk, c, on)
        }
    }

    /** Re-evaluates any repeaters adjacent to [aroundPos] (e.g. a toggled source behind a repeater). */
    private fun updateRepeaters(chunk: KryptonChunk, aroundPos: Vec3i) {
        for (o in NEIGHBOUR_OFFSETS) {
            val p = Vec3i(aroundPos.x + o.x, aroundPos.y + o.y, aroundPos.z + o.z)
            if (wBlock(chunk.world, p).eq(KryptonBlocks.REPEATER)) scheduleRepeater(chunk, p)
        }
    }

    /** True if the block behind the repeater (its input side, opposite FACING) is currently emitting power. */
    private fun repeaterInputOn(chunk: KryptonChunk, state: KryptonBlockState, pos: Vec3i): Boolean {
        val facing = state.requireProperty(KryptonProperties.HORIZONTAL_FACING)
        val back = Vec3i(pos.x - facing.normalX, pos.y - facing.normalY, pos.z - facing.normalZ)
        val b = wBlock(chunk.world, back)
        return isPoweredSource(b) ||
            (b.eq(KryptonBlocks.REDSTONE_WIRE) && b.requireProperty(KryptonProperties.POWER) > 0) ||
            (b.eq(KryptonBlocks.REPEATER) && b.requireProperty(KryptonProperties.POWERED))
    }

    /**
     * Schedules a repeater's output to follow its input after the repeater's delay (DELAY×2 ticks). When it flips,
     * it powers/unpowers the consumer on its FACING (output) side, and re-evaluates a repeater chained in front.
     * Blocks are read/written per-position (`wBlock`/`wSet`), so the repeater may sit in a different chunk than the source.
     */
    private fun scheduleRepeater(chunk: KryptonChunk, pos: Vec3i) {
        val world = chunk.world
        val state = wBlock(world, pos)
        if (!state.eq(KryptonBlocks.REPEATER)) return
        if (state.requireProperty(KryptonProperties.POWERED) == repeaterInputOn(chunk, state, pos)) return // nothing pending
        val delayTicks = state.requireProperty(KryptonProperties.DELAY) * 2
        server.scheduler.buildTask {
            val cur = wBlock(world, pos)
            if (!cur.eq(KryptonBlocks.REPEATER)) return@buildTask
            val now = repeaterInputOn(chunk, cur, pos) // re-check at fire time (input may have changed during the delay)
            if (cur.requireProperty(KryptonProperties.POWERED) != now) {
                wSet(world, pos, cur.setProperty(KryptonProperties.POWERED, now))
                broadcastBlockUpdate(pos, wBlock(world, pos))
                val facing = cur.requireProperty(KryptonProperties.HORIZONTAL_FACING)
                val front = Vec3i(pos.x + facing.normalX, pos.y + facing.normalY, pos.z + facing.normalZ)
                if (isConsumer(wBlock(world, front))) applyConsumer(chunk, front, now)
                if (wBlock(world, front).eq(KryptonBlocks.REPEATER)) scheduleRepeater(chunk, front) // chain repeaters
                recomputeRedstone(chunk, pos) // if a redstone_wire is in front, propagate the repeater's output into it
            }
        }.delay(TaskTime.ticks(delayTicks)).executionType(ExecutionType.SYNCHRONOUS).schedule()
    }

    /** The block one step in the direction the repeater faces (its output side). */
    private fun repeaterFront(state: KryptonBlockState, pos: Vec3i): Vec3i {
        val facing = state.requireProperty(KryptonProperties.HORIZONTAL_FACING)
        return Vec3i(pos.x + facing.normalX, pos.y + facing.normalY, pos.z + facing.normalZ)
    }

    /** Re-evaluates every known comparator (cheap: the set is small) — called when a container's contents change. */
    private fun reevaluateComparators(world: KryptonWorld) {
        for (pos in comparators.toList()) evaluateComparator(world, pos)
    }

    /**
     * Comparator in container-reading mode: reads the container behind it (its input side, `pos - facing`) and
     * outputs a signal proportional to fullness — sets the comparator's POWERED and drives the front consumer.
     */
    private fun evaluateComparator(world: KryptonWorld, pos: Vec3i) {
        val chunk = world.chunkManager.getChunk(ChunkPos(pos.x shr 4, pos.z shr 4)) ?: return
        val state = chunk.getBlock(pos)
        if (!state.eq(KryptonBlocks.COMPARATOR)) { comparators.remove(pos); return } // comparator was removed
        val out = comparatorOutput(world, pos)
        val powered = out > 0
        setBoolProperty(chunk, pos, state, KryptonProperties.POWERED, powered)
        val facing = state.requireProperty(KryptonProperties.HORIZONTAL_FACING)
        val frontPos = Vec3i(pos.x + facing.normalX, pos.y + facing.normalY, pos.z + facing.normalZ)
        val frontChunk = world.chunkManager.getChunk(ChunkPos(frontPos.x shr 4, frontPos.z shr 4)) ?: return
        if (isConsumer(frontChunk.getBlock(frontPos))) applyConsumer(frontChunk, frontPos, powered)
        // If a redstone_wire is in front, propagate the comparator's output level into it. We set inComparatorEval so the
        // nested recompute skips re-evaluating comparators (which would recurse back here) — its wire flood-fill still runs.
        inComparatorEval.set(true)
        try {
            recomputeRedstone(chunk, pos)
        } finally {
            inComparatorEval.set(false)
        }
    }

    /** The comparator's output signal level (0-15): back input (container fullness or redstone) through its mode vs the side inputs. */
    private fun comparatorOutput(world: KryptonWorld, pos: Vec3i): Int {
        val state = world.getBlock(pos.x, pos.y, pos.z)
        if (!state.eq(KryptonBlocks.COMPARATOR)) return 0
        val facing = state.requireProperty(KryptonProperties.HORIZONTAL_FACING)
        val backPos = Vec3i(pos.x - facing.normalX, pos.y - facing.normalY, pos.z - facing.normalZ)
        val back = world.getBlock(backPos.x, backPos.y, backPos.z)
        // Back input: a container's fullness if there's a container behind, otherwise the redstone level behind.
        val backSignal = if (keyValue(back) == "chest") KryptonChestInventory.comparatorSignal(backPos.toString())
                         else redstoneSignalAt(world, backPos)
        // Side inputs: the two blocks perpendicular to facing (left/right of the comparator).
        val side1: Vec3i; val side2: Vec3i
        if (facing.normalX != 0) { side1 = Vec3i(pos.x, pos.y, pos.z + 1); side2 = Vec3i(pos.x, pos.y, pos.z - 1) }
        else { side1 = Vec3i(pos.x + 1, pos.y, pos.z); side2 = Vec3i(pos.x - 1, pos.y, pos.z) }
        val maxSide = maxOf(redstoneSignalAt(world, side1), redstoneSignalAt(world, side2))
        val subtract = state.requireProperty(KryptonProperties.COMPARATOR_MODE) == ComparatorMode.SUBTRACT
        // Compare mode: pass the back signal unless a side input is stronger. Subtract mode: back minus the strongest side.
        return if (subtract) maxOf(0, backSignal - maxSide) else if (backSignal >= maxSide) backSignal else 0
    }

    /** Redstone signal level (0-15) emitted by the block at [pos] (lever/button/plate=15, wire=POWER, repeater/comparator=15 if powered). */
    private fun redstoneSignalAt(world: KryptonWorld, pos: Vec3i): Int {
        val s = world.getBlock(pos.x, pos.y, pos.z)
        val key = keyValue(s)
        return when {
            s.eq(KryptonBlocks.LEVER) || key.endsWith("_button") || key.endsWith("_pressure_plate") ->
                if (s.hasProperty(KryptonProperties.POWERED) && s.requireProperty(KryptonProperties.POWERED)) 15 else 0
            s.eq(KryptonBlocks.REDSTONE_WIRE) -> s.requireProperty(KryptonProperties.POWER)
            s.eq(KryptonBlocks.REPEATER) || s.eq(KryptonBlocks.COMPARATOR) ->
                if (s.requireProperty(KryptonProperties.POWERED)) 15 else 0
            else -> 0
        }
    }

    /** True if [state] is a redstone power source that is currently emitting (lever/button/plate powered). */
    private fun isPoweredSource(state: KryptonBlockState): Boolean {
        val key = keyValue(state)
        return when {
            state.eq(KryptonBlocks.LEVER) || key.endsWith("_button") || key.endsWith("_pressure_plate") ->
                state.hasProperty(KryptonProperties.POWERED) && state.requireProperty(KryptonProperties.POWERED)
            else -> false
        }
    }

    private fun keyValue(state: KryptonBlockState): String = KryptonRegistries.BLOCK.getKey(state.block).value()

    private fun broadcastBlockUpdate(position: Vec3i, state: KryptonBlockState) {
        val packet = PacketOutBlockUpdate(position, state)
        player.world.players.forEach { it.connection.send(packet) }
    }

    fun handlePlayerAction(packet: PacketInPlayerAction) {
        when (packet.action) {
            PlayerAction.START_DIGGING, PlayerAction.FINISH_DIGGING, PlayerAction.CANCEL_DIGGING -> {
                player.gameModeSystem.handleBlockBreak(packet)
                // Acknowledge the block action so the client can reconcile its prediction (1.19.3 sequence).
                player.connection.send(PacketOutAcknowledgeBlockChange(packet.sequence))
            }
            PlayerAction.RELEASE_USE_ITEM -> {
                val handler = player.inventory.getItem(player.inventory.heldSlot).type.handler()
                if (handler is ItemTimedHandler) handler.finishUse(player, player.hand)
            }
            // Q drops one item from the held stack; Ctrl+Q (DROP_ITEM_STACK) drops the whole stack.
            PlayerAction.DROP_ITEM -> dropHeldItem(whole = false)
            PlayerAction.DROP_ITEM_STACK -> dropHeldItem(whole = true)
            else -> Unit
        }
    }

    /** Tosses the held item onto the ground as a real [KryptonItemEntity] that can be picked up after a short delay. */
    private fun dropHeldItem(whole: Boolean) {
        val held = player.inventory.getHeldItem(Hand.MAIN)
        if (held === KryptonItemStack.EMPTY || held.amount <= 0) return
        val tossAmount = if (whole) held.amount else 1
        val remaining = held.amount - tossAmount
        player.inventory.setHeldItem(Hand.MAIN, if (remaining <= 0) KryptonItemStack.EMPTY else held.withAmount(remaining))

        val drop = KryptonItemEntity(player.world)
        val pos = player.position
        drop.position = pos.withCoordinates(pos.x, pos.y + 1.0, pos.z) // toss from roughly eye height
        drop.item = held.withAmount(tossAmount)
        drop.pickupDelay = TOSS_PICKUP_DELAY_CYCLES // so the tosser doesn't instantly re-collect it
        // Launch it in the direction the player is looking (like vanilla), so it flies out instead of dropping straight down.
        val yawRad = Math.toRadians(pos.yaw.toDouble())
        val pitchRad = Math.toRadians(pos.pitch.toDouble())
        drop.velocity = Vec3d(
            -Math.sin(yawRad) * Math.cos(pitchRad) * TOSS_SPEED,
            -Math.sin(pitchRad) * TOSS_SPEED + TOSS_UPWARD,
            Math.cos(yawRad) * Math.cos(pitchRad) * TOSS_SPEED
        )
        player.world.spawnEntity(drop)
        ItemDropManager.add(drop)
        ensureContainerTick() // the shared tick drives ItemDropManager (pickup + despawn)
    }

    /** Resolves a potion's effect from its "Potion" NBT tag (e.g. minecraft:long_swiftness -> Speed); defaults to Speed. */
    private fun potionEffectId(item: KryptonItemStack): Int {
        val potion = item.meta.data.getString("Potion").substringAfterLast(':').removePrefix("long_").removePrefix("strong_")
        return POTION_EFFECTS[potion] ?: EFFECT_SPEED
    }

    /** Applies a timed status effect to [target]: sends the Entity Effect packet and tracks it for server-side expiry. */
    private fun applyPotionEffect(target: KryptonPlayer, effectId: Int) {
        target.connection.send(PacketOutEntityEffect(target.id, effectId, 0, POTION_EFFECT_DURATION_TICKS, EFFECT_FLAGS_VISIBLE))
        PlayerEffectManager.add(target, effectId, (POTION_EFFECT_DURATION_TICKS + SHARED_TICK_TICKS - 1) / SHARED_TICK_TICKS)
    }

    fun handleUseItem(packet: PacketInUseItem) {
        val held = player.inventory.getHeldItem(packet.hand)
        val name = held.type.key().value()
        // Drinking a potion applies the status effect named by its "Potion" NBT tag (e.g. minecraft:swiftness -> Speed);
        // plain water bottles and unknown potions fall back to Speed. (`long_`/`strong_` variants map to the base effect.)
        if (name == "potion") {
            applyPotionEffect(player, potionEffectId(held)) // drink: apply to self
            ensureContainerTick()
            held.type.handler().use(player, packet.hand)
            return
        }
        // Throwing a splash potion: spawn a real thrown-potion projectile that flies in the look direction and, after a
        // short flight, bursts and applies its effect to nearby players (see KryptonThrownPotion). The effect to apply
        // is read from the item's "Potion" NBT (like drinking).
        if (name == "splash_potion") {
            val pos = player.position
            val potion = KryptonThrownPotion(player.world)
            potion.effectId = potionEffectId(held)
            potion.position = pos.withCoordinates(pos.x, pos.y + THROW_EYE_HEIGHT, pos.z)
            val yawRad = Math.toRadians(pos.yaw.toDouble())
            val pitchRad = Math.toRadians(pos.pitch.toDouble())
            potion.velocity = Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad) * SPLASH_THROW_SPEED,
                -Math.sin(pitchRad) * SPLASH_THROW_SPEED,
                Math.cos(yawRad) * Math.cos(pitchRad) * SPLASH_THROW_SPEED
            )
            player.world.spawnEntity(potion)
            ensureContainerTick() // the shared tick expires the effects the burst applies
            held.type.handler().use(player, packet.hand)
            return
        }
        // Fishing rod: the first right-click CASTS a bobber that flies out; the next reels it in and catches a fish.
        // v2: the catch is rolled from a loot pool (not always cod) and placed straight into a free inventory slot.
        // Simplified: every reel catches, with no bite-timing or water requirement.
        if (name == "fishing_rod") {
            val existing = fishingBobbers[player]
            if (existing != null && !existing.isRemoved()) {
                existing.remove()
                fishingBobbers.remove(player)
                val loot = FISH_LOOT[player.world.random.nextInt(FISH_LOOT.size)] // v2: rolled from a loot pool, not always cod
                val catch = KryptonItemEntity(player.world)
                catch.position = player.position
                catch.item = KryptonItemStack(KryptonRegistries.ITEM.get(Key.key(loot)))
                catch.pickupDelay = 0
                player.world.spawnEntity(catch)
                ItemDropManager.add(catch)
                ensureContainerTick()
            } else {
                val pos = player.position
                val bobber = KryptonFishingHook(player.world)
                bobber.position = pos.withCoordinates(pos.x, pos.y + THROW_EYE_HEIGHT, pos.z)
                val yawRad = Math.toRadians(pos.yaw.toDouble())
                val pitchRad = Math.toRadians(pos.pitch.toDouble())
                bobber.velocity = Vec3d(
                    -Math.sin(yawRad) * Math.cos(pitchRad) * BOBBER_CAST_SPEED,
                    -Math.sin(pitchRad) * BOBBER_CAST_SPEED,
                    Math.cos(yawRad) * Math.cos(pitchRad) * BOBBER_CAST_SPEED
                )
                player.world.spawnEntity(bobber)
                fishingBobbers[player] = bobber
            }
            return
        }
        // Eating edible food restores hunger (only when not already full); the foodLevel setter pushes a SetHealth update.
        // Simplified: the food is consumed instantly rather than over the vanilla eat-animation duration.
        if (held.type.isEdible) {
            if (player.foodLevel < FoodHandler.MAX_FOOD_LEVEL) FoodHandler.finishUse(player, packet.hand)
            return // an edible item never falls through to the spawn-egg / cow path below
        }
        // Spawn eggs: right-clicking a "<mob>_spawn_egg" spawns that mob; otherwise (dev fallback) a cow.
        val mobId = if (name.endsWith("_spawn_egg")) name.removeSuffix("_spawn_egg") else "cow"
        val entity = EntityFactory.create(player.world, mobId, null) ?: KryptonCow(player.world)
        entity.position = player.position
        player.world.spawnEntity(entity)
        MobMover.add(entity)
        ensureContainerTick() // the shared tick also drives MobMover.tick()
        held.type.handler().use(player, packet.hand)
    }

    fun handlePlayerInput(packet: PacketInPlayerInput) {
        // TODO: Handle steering here
        if (packet.isSneaking()) player.ejectVehicle()
    }

    fun handleInteract(packet: PacketInInteract) {
        val target = player.world.entityManager.getById(packet.entityId) ?: return
        if (player.position.distanceSquared(target.position) >= INTERACTION_RANGE_SQUARED) return

        when (packet.action) {
            is PacketInInteract.InteractAction -> onInteract(target, packet.action.hand)
            is PacketInInteract.AttackAction -> onAttack(target)
            is PacketInInteract.InteractAtAction -> onInteractAt(target, packet.action.hand, packet.action.x, packet.action.y, packet.action.z)
        }
    }

    private fun onInteract(target: KryptonEntity, hand: Hand) {
        val event = server.eventNode.fire(KryptonPlayerInteractWithEntityEvent(player, target, hand))
        if (!event.isAllowed()) return

        // Milking: right-clicking a cow with an empty bucket fills it with milk.
        if (target.type.key().value() == "cow" && player.inventory.getHeldItem(Hand.MAIN).type.key().value() == "bucket") {
            player.inventory.setHeldItem(Hand.MAIN, KryptonItemStack(KryptonRegistries.ITEM.get(Key.key("milk_bucket"))))
            return
        }

        // Shearing: right-clicking a not-yet-sheared sheep with shears marks it sheared (metadata) and drops its wool.
        if (target is KryptonSheep && !target.isSheared && player.inventory.getHeldItem(Hand.MAIN).type.key().value() == "shears") {
            target.isSheared = true // sets the SHEARED flag in Sheep.FLAGS (metadata index 17) -> broadcast to nearby players
            val woolName = target.woolColor.name.lowercase() + "_wool"
            val drop = KryptonItemEntity(target.world)
            drop.position = target.position
            drop.item = KryptonItemStack(KryptonRegistries.ITEM.get(Key.key(woolName))).withAmount(1 + target.world.random.nextInt(3))
            target.world.spawnEntity(drop)
            ItemDropManager.add(drop)
            ensureContainerTick() // shared tick drives ItemDropManager (pickup + despawn)
            return
        }

        // Dyeing: right-clicking a sheep with a dye recolours its wool to that dye's colour.
        if (target is KryptonSheep) {
            val dyeKey = player.inventory.getHeldItem(Hand.MAIN).type.key().value()
            if (dyeKey.endsWith("_dye")) {
                val color = DyeColor.values().firstOrNull { it.name.equals(dyeKey.removeSuffix("_dye"), ignoreCase = true) }
                if (color != null && target.woolColor != color) {
                    target.woolColor = color // updates the colour bits of Sheep.FLAGS (metadata index 17) -> broadcast
                    if (player.gameMode != GameMode.CREATIVE) {
                        val held = player.inventory.getHeldItem(Hand.MAIN)
                        player.inventory.setHeldItem(Hand.MAIN, if (held.amount <= 1) KryptonItemStack.EMPTY else held.withAmount(held.amount - 1))
                    }
                    return
                }
            }
        }

        // Animal breeding: feeding an animal its food puts it in "love mode"; two in-love animals nearby breed a baby.
        if (target is KryptonAnimal) {
            val held = player.inventory.getHeldItem(Hand.MAIN)
            if (target.isFood(held) && target.canFallInLove()) {
                target.setLoveCause(player)
                val remaining = held.amount - 1
                player.inventory.setHeldItem(Hand.MAIN, if (remaining <= 0) KryptonItemStack.EMPTY else held.withAmount(remaining))
                tryBreed(target)
            }
        }
    }

    /** If [animal] is in love and a same-type in-love animal is nearby, spawn a baby between them and reset both. */
    private fun tryBreed(animal: KryptonAnimal) {
        if (!animal.isInLove()) return
        val partner = animal.world.entityManager.entities().firstOrNull {
            it !== animal && it is KryptonAnimal && it.javaClass == animal.javaClass && it.isInLove() &&
                it.position.distanceSquared(animal.position) <= BREED_RADIUS_SQUARED
        } as? KryptonAnimal ?: return
        val baby = EntityFactory.create(animal.world, animal.type.key().value(), null) as? KryptonAnimal ?: return
        baby.position = Position(
            (animal.position.x + partner.position.x) / 2.0, animal.position.y, (animal.position.z + partner.position.z) / 2.0, 0F, 0F
        )
        baby.isBaby = true // metadata BABY=true so the client renders a calf/piglet/etc.
        animal.world.spawnEntity(baby)
        MobMover.add(baby) // tracked so it ages up to an adult on the shared tick
        animal.inLoveTime = 0
        partner.inLoveTime = 0
        // Breeding rewards a little XP, like vanilla.
        val orb = KryptonExperienceOrb(animal.world)
        orb.position = baby.position
        orb.experience = 3
        animal.world.spawnEntity(orb)
        ExperienceOrbManager.add(orb)
        ensureContainerTick() // drives MobMover (baby grows up) + ExperienceOrbManager
    }

    private fun onInteractAt(target: KryptonEntity, hand: Hand, x: Float, y: Float, z: Float) {
        val clickedPosition = Vec3d(x.toDouble(), y.toDouble(), z.toDouble())
        val event = server.eventNode.fire(KryptonPlayerInteractAtEntityEvent(player, target, hand, clickedPosition))
        if (!event.isAllowed()) return

        // TODO: Re-implement interactions and call a handler here
    }

    private fun onAttack(target: KryptonEntity) {
        val event = server.eventNode.fire(KryptonPlayerAttackEntityEvent(player, target))
        if (!event.isAllowed()) return
        // Player-vs-player combat: damage + knockback, leaving the victim dead at 0 HP (they respawn on client command).
        if (target is KryptonPlayer) {
            attackPlayer(target)
            return
        }
        // Basic melee combat (mob increment 3): damage a living target; despawn it when its health runs out.
        if (target is KryptonLivingEntity) {
            target.health = target.health - 4F
            if (target.health <= 0F) {
                // Death drop: an experience orb (XP) plus a real dropped-item entity (loot) where the mob died.
                val orb = KryptonExperienceOrb(target.world)
                orb.position = target.position
                orb.experience = 3
                target.world.spawnEntity(orb)
                ExperienceOrbManager.add(orb) // a nearby player collects it on the shared tick (XP + level up)

                // Item ground-drop (mob increment 5): spawn a KryptonItemEntity carrying this mob's loot. A nearby
                // player auto-collects it via ItemDropManager (driven by the shared tick), which also gives the item.
                val dropName = DEATH_DROPS.getOrDefault(target.type.key().value(), "bone")
                val drop = KryptonItemEntity(target.world)
                drop.position = target.position
                drop.item = KryptonItemStack(KryptonRegistries.ITEM.get(Key.key(dropName)))
                target.world.spawnEntity(drop)
                ItemDropManager.add(drop)
                ensureContainerTick() // the shared tick also drives ItemDropManager.tick() (pickup)
                target.remove()
            }
        }

        // TODO: Re-implement interactions and call a handler here
    }

    /** PvP: deal melee damage to [target] (the setter pushes Update Health to the victim) and knock them back from the attacker. */
    private fun attackPlayer(target: KryptonPlayer) {
        if (target.health <= 0F) return // already down — ignore hits until they respawn
        target.health = (target.health - PVP_DAMAGE).coerceAtLeast(0F)

        // Knockback: shove the victim horizontally away from the attacker, with a slight upward pop.
        val dx = target.position.x - player.position.x
        val dz = target.position.z - player.position.z
        val dist = sqrt(dx * dx + dz * dz)
        val (vx, vz) = if (dist > 1.0E-4) (dx / dist) * KNOCKBACK_HORIZONTAL to (dz / dist) * KNOCKBACK_HORIZONTAL else 0.0 to 0.0
        val velocity = PacketOutSetEntityVelocity(target.id, Positioning.encodeVelocity(vx), Positioning.encodeVelocity(KNOCKBACK_VERTICAL), Positioning.encodeVelocity(vz))
        target.connection.send(velocity)        // the victim's own client applies the knockback
        target.sendPacketToViewers(velocity)     // everyone else sees them fly back
    }

    fun handlePlayerPosition(packet: PacketInSetPlayerPosition) {
        handlePositionRotationUpdate(player.position.withCoordinates(packet.x, packet.y, packet.z), packet.onGround)
    }

    fun handlePlayerRotation(packet: PacketInSetPlayerRotation) {
        handlePositionRotationUpdate(player.position.withRotation(packet.yaw, packet.pitch), packet.onGround)
    }

    fun handlePlayerPositionAndRotation(packet: PacketInSetPlayerPositionAndRotation) {
        handlePositionRotationUpdate(packet.position(), packet.onGround)
    }

    private fun handlePositionRotationUpdate(newPosition: Position, onGround: Boolean) {
        val oldPosition = player.position
        if (oldPosition == newPosition) return // Position hasn't changed, no need to do anything

        // TODO: Figure out if we should make an entity move event and move this there, so the event is called on teleportation too
        val event = server.eventNode.fire(KryptonPlayerMoveEvent(player, oldPosition, newPosition))
        if (!event.isAllowed()) return

        // Server-side block collision (anti-clip): reject moves that would push the player into a solid block and
        // snap the client back to its last valid position. Only the destination is checked (no swept collision yet).
        if (collidesWithSolid(newPosition)) {
            connection.send(PacketOutSynchronizePlayerPosition(oldPosition.x, oldPosition.y, oldPosition.z, oldPosition.yaw, oldPosition.pitch))
            return
        }

        player.isOnGround = onGround
        player.teleport(newPosition)
        updatePressurePlate(newPosition) // redstone: power a pressure plate the player steps onto
    }

    /** True if the player's bounding box at [position] overlaps any solid block (full-cube approximation). */
    private fun collidesWithSolid(position: Position): Boolean {
        val world = player.world
        // Enumerate the block cells the (epsilon-shrunk) player AABB occupies; any solid one = collision. The
        // epsilon stops a player resting exactly on a block's top/side face from counting as penetration.
        val minX = floor(position.x - PLAYER_HALF_WIDTH + COLLISION_EPSILON).toInt()
        val maxX = floor(position.x + PLAYER_HALF_WIDTH - COLLISION_EPSILON).toInt()
        val minY = floor(position.y + COLLISION_EPSILON).toInt()
        val maxY = floor(position.y + PLAYER_HEIGHT - COLLISION_EPSILON).toInt()
        val minZ = floor(position.z - PLAYER_HALF_WIDTH + COLLISION_EPSILON).toInt()
        val maxZ = floor(position.z + PLAYER_HALF_WIDTH - COLLISION_EPSILON).toInt()
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val block = world.getBlock(x, y, z)
            // Pressure plates are walkable (you stand ON them to trigger them), so they don't block movement
            // despite reporting a collision under the full-cube approximation.
            if (block.isSolid() && !keyValue(block).endsWith("_pressure_plate")) return true
        }
        return false
    }

    fun handlePluginMessage(packet: PacketInPluginMessage) {
        server.eventNode.fire(KryptonPluginMessageReceivedEvent(player, packet.channel, packet.data))
    }

    fun handleCommandSuggestionsRequest(packet: PacketInCommandSuggestionsRequest) {
        val reader = StringReader(packet.command)
        if (reader.canRead() && reader.peek() == '/') reader.skip()
        val parseResults = server.commandManager.parse(player.createCommandSourceStack(), reader)
        server.commandManager.suggest(parseResults)
            .thenAcceptAsync { connection.send(PacketOutCommandSuggestionsResponse(packet.id, it)) }
    }

    fun handleClientCommand(packet: PacketInClientCommand) {
        when (packet.action) {
            PacketInClientCommand.Action.PERFORM_RESPAWN -> respawnPlayer()
            PacketInClientCommand.Action.REQUEST_STATS -> player.statisticsTracker.sendUpdated()
        }
    }

    /**
     * Full respawn: send a Respawn packet so the client discards its world and dismisses the death screen, restore
     * full health/hunger, teleport to the spawn point, then re-send chunks and a position sync for the fresh world.
     */
    private fun respawnPlayer() {
        val world = player.world
        // Respawn packet — the client clears its world (this is what closes the death screen) and waits for new data.
        connection.send(PacketOutRespawn(
            KryptonDynamicRegistries.DIMENSION_TYPE.getResourceKey(world.dimensionType)!!,
            world.dimension,
            BiomeManager.obfuscateSeed(world.seed),
            player.gameModeSystem.gameMode(),
            player.gameModeSystem.previousGameMode(),
            isDebug = false,
            isFlat = false,
            dataKept = 0 // death respawn keeps neither attributes nor metadata
        ))
        player.health = player.maxHealth // setter pushes Update Health (full hearts) to the client
        player.foodLevel = MAX_FOOD_LEVEL
        player.foodSaturationLevel = MAX_FOOD_LEVEL.toFloat()
        val spawn = player.respawnData?.position ?: world.data.spawnPos()
        val target = player.position.withCoordinates(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5)
        player.teleport(target)
        player.sendInitialChunks() // re-send the world the client just discarded, around the spawn point
        connection.send(PacketOutSynchronizePlayerPosition(target.x, target.y, target.z, target.yaw, target.pitch))
    }

    fun handleEntityTagQuery(packet: PacketInQueryEntityTag) {
        if (!player.hasPermission(KryptonPermission.ENTITY_QUERY.node)) return
        val entity = player.world.entityManager.getById(packet.entityId) ?: return
        connection.send(PacketOutTagQueryResponse(packet.transactionId, entity.saveWithPassengers().build()))
    }

    fun handleResourcePack(packet: PacketInResourcePack) {
        if (packet.status == ResourcePack.Status.DECLINED && server.config.server.resourcePack.forced) {
            disconnect(DisconnectMessages.REQUIRED_TEXTURE_PROMPT)
            return
        }
        server.eventNode.fire(KryptonPlayerResourcePackStatusEvent(player, packet.status))
    }

    companion object {

        private const val INTERACTION_RANGE_SQUARED = 6.0 * 6.0
        private const val KEEP_ALIVE_INTERVAL = 15000L
        private const val TOSS_PICKUP_DELAY_CYCLES = 2 // tick cycles a tossed item waits before it can be picked up
        private const val TOSS_SPEED = 0.3   // horizontal launch speed of a tossed item (blocks/tick, in the look direction)
        private const val TOSS_UPWARD = 0.1  // small upward component so a tossed item arcs out, like vanilla

        // Player bounding box for server-side block collision (vanilla player is 0.6 wide, 1.8 tall).
        private const val PLAYER_HALF_WIDTH = 0.3
        private const val PLAYER_HEIGHT = 1.8
        private const val COLLISION_EPSILON = 1.0E-3

        // The six face-adjacent block offsets, used to find redstone lamps next to a toggled lever.
        private val NEIGHBOUR_OFFSETS = listOf(
            Vec3i(1, 0, 0), Vec3i(-1, 0, 0), Vec3i(0, 1, 0), Vec3i(0, -1, 0), Vec3i(0, 0, 1), Vec3i(0, 0, -1)
        )
        private const val BUTTON_PRESS_TICKS = 20 // how long a pressed button stays powered before auto-resetting
        private const val MAX_PISTON_PUSH = 12 // a piston pushes at most this many blocks in a row (vanilla limit)
        // Items whose placed block has a different key than the item (extend as needed).
        private val ITEM_BLOCK_OVERRIDES = mapOf(
            "redstone" to "redstone_wire", "water_bucket" to "water",
            "wheat_seeds" to "wheat", "carrot" to "carrots", "potato" to "potatoes" // crop items place their growing crop block
        )
        private const val WATER_FLOW_RANGE = 6       // how many blocks placed water flows horizontally over ground
        private const val WATER_FLOW_MAX_BLOCKS = 400 // safety cap on a single flow's spread (prevents runaway floods)
        private const val WATER_FALLING_LEVEL = 8    // block `level` for falling water (8..15 = falling; renders full column)
        private const val EFFECT_SPEED = 1               // classic mob-effect id for Speed (Entity Effect 0x68)
        private const val EFFECT_FLAGS_VISIBLE = 0x06    // show particles (0x02) + show icon (0x04)
        private val POTION_EFFECT_DURATION_TICKS = System.getProperty("krypton.potionDurationTicks")?.toIntOrNull() ?: 600 // effect length (test hook)
        private const val SHARED_TICK_TICKS = 20 // the shared container/effect tick runs every 20 ticks (~1 s)
        private const val THROW_EYE_HEIGHT = 1.5 // launch a thrown splash potion from roughly eye height
        private const val SPLASH_THROW_SPEED = 0.5 // per-tick launch speed of a thrown splash potion
        private const val BOBBER_CAST_SPEED = 0.6 // per-tick launch speed of a cast fishing bobber
        private val fishingBobbers = java.util.concurrent.ConcurrentHashMap<KryptonPlayer, KryptonFishingHook>() // active cast bobber per player
        private val FISH_LOOT = listOf("cod", "salmon", "pufferfish", "tropical_fish") // a reeled-in catch is rolled from this pool
        private const val PLAYER_INVENTORY_SIZE = 36 // backing item list: 27 main + 9 hotbar (indices 0..35)
        private const val MAX_CROP_AGE = 7 // AGE_7 property max — bone meal jumps a crop straight to this
        private const val COMPOSTER_FULL = 8 // COMPOSTER_LEVEL max (0..8); a full composter is harvested for bone meal
        // A representative subset of compostable items (vanilla has ~40); composting one raises a composter's level.
        private val COMPOSTABLES = setOf(
            "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "wheat", "carrot", "potato", "apple",
            "melon_slice", "sugar_cane", "kelp", "dried_kelp", "cactus", "sweet_berries", "oak_sapling", "spruce_sapling",
            "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "oak_leaves", "spruce_leaves",
            "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "grass", "fern", "dandelion", "poppy",
            "bread", "cookie", "pumpkin", "melon", "mushroom_stem", "nether_wart", "vine", "lily_pad"
        )
        // Potion "Potion" NBT tag (base name) -> classic mob-effect id. Unknown/plain potions fall back to Speed.
        private val POTION_EFFECTS = mapOf(
            "swiftness" to 1, "slowness" to 2, "strength" to 5, "healing" to 6, "harming" to 7, "leaping" to 8,
            "regeneration" to 10, "fire_resistance" to 12, "water_breathing" to 13, "invisibility" to 14,
            "night_vision" to 16, "weakness" to 18, "poison" to 19
        )
        private const val DAY_LENGTH = 24000L     // ticks per Minecraft day
        private const val NIGHT_START = 12542L    // dayTime-of-day when sleeping becomes allowed (dusk)
        private const val NIGHT_END = 23459L      // dayTime-of-day when sleeping stops being allowed (pre-dawn)
        private val SLEEP_ANYTIME = System.getProperty("krypton.allowSleepAnytime")?.toBoolean() ?: false // test hook
        private const val SLEEP_POSE_TICKS = 40 // how long the player visibly lies in the bed before waking (2 s)
        private const val PVP_DAMAGE = 4F            // half-heart * 4 = 2 hearts per hit (vanilla bare-hand-ish)
        private const val KNOCKBACK_HORIZONTAL = 0.4 // blocks/tick pushed away from the attacker
        private const val KNOCKBACK_VERTICAL = 0.36  // slight upward pop, like vanilla knockback
        private const val MAX_FOOD_LEVEL = 20        // full hunger bar restored on respawn
        private const val BREED_RADIUS_SQUARED = 64.0 // two in-love animals within 8 blocks breed
        private val comparators = java.util.concurrent.ConcurrentHashMap.newKeySet<Vec3i>() // placed comparators (global)
        private val poweredDispensers = java.util.concurrent.ConcurrentHashMap.newKeySet<Vec3i>() // dispensers currently powered (edge detection)
        // Reentrancy guard for the recompute -> reevaluateComparators -> evaluateComparator -> recompute cycle.
        // While a comparator is propagating into its FRONT wire, we skip re-evaluating comparators again (breaks the
        // cycle) but still run that comparator's own wire flood-fill, so a comparator fed by a lever/wire (not just a
        // container) also drives the wire network in front of it.
        private val inComparatorEval = ThreadLocal.withInitial { false }
        private val LOGGER = LogManager.getLogger()

        // Loot dropped when a mob dies, keyed by entity-type name; anything else drops a bone.
        private val DEATH_DROPS = mapOf(
            "cow" to "leather",
            "zombie" to "rotten_flesh",
            "chicken" to "feather",
            "pig" to "porkchop",
            "sheep" to "mutton"
        )
    }
}
