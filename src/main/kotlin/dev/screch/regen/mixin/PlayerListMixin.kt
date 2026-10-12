package dev.screch.regen.mixin

import dev.screch.regen.RegenPlayerConnection
import dev.screch.regen.RegenPlayerList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.PlayerAdvancements
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.PlayerList
import net.minecraft.stats.ServerStatsCounter
import net.minecraft.stats.Stat
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.portal.TeleportTransition
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import java.util.UUID
import java.util.EnumSet

@Mixin(PlayerList::class)
abstract class PlayerListMixin : RegenPlayerList {
    @field:Shadow @field:Final private lateinit var server: MinecraftServer
    @field:Shadow @field:Final private lateinit var players: MutableList<ServerPlayer>
    @field:Shadow @field:Final private lateinit var playersByUUID: MutableMap<UUID, ServerPlayer>
    @field:Shadow @field:Final private lateinit var stats: MutableMap<UUID, ServerStatsCounter>
    @field:Shadow @field:Final private lateinit var advancements: MutableMap<UUID, PlayerAdvancements>
    @Shadow abstract fun sendLevelInfo(player: ServerPlayer, level: ServerLevel)
    @Shadow abstract fun sendPlayerPermissionLevel(player: ServerPlayer)

    override fun regenDetachPlayers(): List<ServerPlayer> {
        val previous = players.toList()
        for (player in previous) {
            val clearedStats = Object2IntOpenHashMap<Stat<*>>()
            for (stat in (player.stats as StatsCounterAccessor).regenGetStats().keys) clearedStats.put(stat, 0)
            player.connection.send(ClientboundAwardStatsPacket(clearedStats))
            player.closeContainer()
            player.stopRiding()
            player.advancements.clearTriggers()
            server.customBossEvents.onPlayerDisconnect(player)
            player.level().removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)
        }
        players.clear()
        playersByUUID.clear()
        stats.clear()
        advancements.clear()
        return previous
    }

    override fun regenRestorePlayers(previous: List<ServerPlayer>) {
        val level = server.overworld()
        for (oldPlayer in previous) {
            if (!oldPlayer.connection.isAcceptingMessages) continue
            val player = ServerPlayer(server, level, oldPlayer.gameProfile, oldPlayer.clientInformation())
            player.connection = oldPlayer.connection
            player.id = oldPlayer.id
            oldPlayer.chatSession?.let { player.setChatSession(it) }
            val spawn = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)
            player.snapTo(spawn.position().x, spawn.position().y, spawn.position().z, spawn.yRot(), spawn.xRot())
            (player.connection as RegenPlayerConnection).regenReplacePlayer(player)
            val info = player.createCommonSpawnInfo(level)
            // A dimension change discards the client's old chunk and biome caches
            val transition = CommonPlayerSpawnInfo(info.dimensionType(), Level.NETHER, info.seed(), info.gameType(),
                info.previousGameType(), info.isDebug(), info.isFlat(), info.lastDeathLocation(), info.portalCooldown(), info.seaLevel())
            player.connection.send(ClientboundRespawnPacket(transition, 0))
            player.connection.send(ClientboundRespawnPacket(info, 0))
            player.connection.teleport(player.x, player.y, player.z, player.yRot, player.xRot)
            player.connection.send(ClientboundChangeDifficultyPacket(server.worldData.difficulty, server.worldData.isDifficultyLocked))
            player.connection.send(ClientboundPlayerAbilitiesPacket(player.abilities))
            player.connection.send(ClientboundUpdateAttributesPacket(player.id, player.attributes.syncableAttributes))
            player.connection.send(ClientboundEntityEventPacket(player,
                (if (level.gameRules.get(GameRules.REDUCED_DEBUG_INFO)) 22 else 23).toByte()))
            player.connection.send(ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN,
                if (level.gameRules.get(GameRules.IMMEDIATE_RESPAWN)) 1f else 0f))
            player.connection.send(ClientboundGameEventPacket(ClientboundGameEventPacket.LIMITED_CRAFTING,
                if (level.gameRules.get(GameRules.LIMITED_CRAFTING)) 1f else 0f))
            player.connection.send(ClientboundSetHeldSlotPacket(0))
            player.connection.send(ClientboundSetExperiencePacket(0f, 0, 0))
            player.recipeBook.sendInitialRecipeBook(player)
            player.connection.send(ClientboundUpdateAdvancementsPacket(true, emptyList(), emptySet(), emptyMap(), false))
            player.advancements.flushDirty(player, false)
            sendLevelInfo(player, level)
            sendPlayerPermissionLevel(player)
            level.addRespawnedPlayer(player)
            players.add(player)
            playersByUUID[player.uuid] = player
            player.initInventoryMenu()
            player.health = player.health
            server.customBossEvents.onPlayerConnect(player)
        }
        server.playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), players))
    }
}
