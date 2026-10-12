package dev.screch.regen

import com.mojang.authlib.GameProfile
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.stats.Stats
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.nio.file.Files
import java.util.UUID

class LiveRegenerationTest : ModInitializer {
    private val connections = mutableListOf<RecordingConnection>()
    private lateinit var previousPlayers: List<ServerPlayer>
    private lateinit var previousLevel: ServerLevel
    private var previousSeed = 0L
    private var stage = 0
    private var ticks = 0

    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (server.isDedicatedServer) setup(server)
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (server.isDedicatedServer && ++ticks % 5 == 0) {
                try {
                    runStage(server)
                } catch (failure: Throwable) {
                    RegenMod.logger.error("REGEN INTEGRATION TEST FAILED", failure)
                    server.halt(false)
                    throw IllegalStateException("Immediate regeneration integration test failed", failure)
                }
            }
        }
    }

    private fun setup(server: MinecraftServer) {
        for (index in 0..1) {
            val level = if (index == 0) server.overworld() else requireNotNull(server.getLevel(Level.NETHER))
            val profile = GameProfile(UUID.nameUUIDFromBytes("regen-player-$index".toByteArray()), "RegenTest$index")
            val player = ServerPlayer(server, level, profile, ClientInformation.createDefault())
            val connection = RecordingConnection(index == 1)
            connections.add(connection)
            ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false))
            val spawn = server.overworld().respawnData.pos()
            player.snapTo(spawn.x.toDouble(), spawn.y.toDouble(), spawn.z.toDouble(), 0f, 0f)
            player.inventory.add(ItemStack(Items.DIAMOND, 32))
            player.enderChestInventory.setItem(0, ItemStack(Items.EMERALD, 12))
            player.giveExperiencePoints(100)
            player.stats.setValue(player, Stats.CUSTOM.get(Stats.JUMP), 123)
            val advancement = requireNotNull(server.advancements.get(Identifier.withDefaultNamespace("story/root")))
            player.advancements.award(advancement, advancement.value().criteria().keys.first())
            server.playerList.players.add(player)
            server.playerList.playersByUUID[player.uuid] = player
            level.addNewPlayer(player)
        }
        server.scoreboard.addObjective("regen_old", ObjectiveCriteria.DUMMY, Component.literal("Old score"),
            ObjectiveCriteria.RenderType.INTEGER, false, null)
        server.scoreboard.addPlayerTeam("regen_old_team")
        server.setWeatherParameters(0, 6000, true, true)
        val world = server.getWorldPath(LevelResource.ROOT)
        for (name in listOf("region", "DIM-1", "DIM1", "players/data", "datapacks")) {
            val fixture = world.resolve(name).resolve("regen-sentinel.txt")
            Files.createDirectories(fixture.parent)
            Files.writeString(fixture, "old-world")
        }
        RegenMod.logger.info("Integration fixture added two players in different dimensions with TCP and memory connection transports")
    }

    private fun runStage(server: MinecraftServer) {
        when (stage++) {
            0 -> {
                snapshot(server)
                command(server, "regen immediate seed with spaces")
                command(server, "regen ignored duplicate seed")
            }
            1 -> {
                verify(server, SeedResolver.parse("immediate seed with spaces"))
                snapshot(server)
                command(server, "regen")
            }
            2 -> {
                val seed = server.worldGenSettings.options().seed()
                check(seed != previousSeed) { "Random seed did not change" }
                verify(server, seed)
                snapshot(server)
                command(server, "regen 0")
            }
            3 -> {
                verify(server, 0)
                RegenMod.logger.info("REGEN INTEGRATION TEST PASSED: immediate text, random and zero seeds; two connected players; terrain, player progress and client packets reset")
                server.halt(false)
            }
        }
    }

    private fun snapshot(server: MinecraftServer) {
        previousLevel = server.overworld()
        previousPlayers = server.playerList.players.toList()
        previousSeed = server.worldGenSettings.options().seed()
        connections.forEach { it.packets.clear() }
    }

    private fun command(server: MinecraftServer, command: String) {
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), command)
    }

    private fun verify(server: MinecraftServer, seed: Long) {
        check(server.isRunning) { "Server stopped during regeneration" }
        check(server.overworld() !== previousLevel) { "Old overworld was retained" }
        check(server.worldGenSettings.options().seed() == seed) { "Generation settings have the wrong seed" }
        for (level in server.allLevels) check(level.seed == seed) { "Dimension has the old seed" }
        check(server.playerList.players.size == 2) { "Player count changed" }
        for (index in 0..1) {
            val oldPlayer = previousPlayers[index]
            val player = requireNotNull(server.playerList.getPlayer(oldPlayer.uuid))
            check(player !== oldPlayer) { "Player data was retained" }
            check(player.level() === server.overworld()) { "Player did not return to the new spawn" }
            check(player.connection === oldPlayer.connection) { "Player connection was replaced" }
            check(player.connection.player === player) { "Connection still references the old player" }
            check(player.inventory.isEmpty) { "Inventory was retained" }
            check(player.enderChestInventory.isEmpty) { "Ender chest was retained" }
            check(player.totalExperience == 0) { "Experience was retained" }
            check(player.stats.getValue(Stats.CUSTOM.get(Stats.JUMP)) == 0) { "Statistics were retained" }
            check(server.playerList.loadPlayerData(player.nameAndId()).isPresent) { "New player data was not saved" }
            val root = requireNotNull(server.advancements.get(Identifier.withDefaultNamespace("story/root")))
            check(!player.advancements.getOrStartProgress(root).isDone) { "Advancements were retained" }
            val connection = connections[index]
            check(!connection.disconnected) { "Player disconnected" }
            check(connection.packets.count { it is ClientboundRespawnPacket } == 2) { "Missing cache reset packets" }
            check(connection.packets.any { it is ClientboundUpdateAdvancementsPacket && it.shouldReset() }) { "Client advancements were not cleared" }
        }
        check(server.scoreboard.getObjective("regen_old") == null) { "Scoreboard was retained" }
        check(server.scoreboard.getPlayerTeam("regen_old_team") == null) { "Team was retained" }
        check(!server.weatherData.isRaining) { "Weather was retained" }
        val world = server.getWorldPath(LevelResource.ROOT)
        for (name in listOf("region", "DIM-1", "DIM1", "players/data")) {
            check(!Files.exists(world.resolve(name).resolve("regen-sentinel.txt"))) { "Old world data was retained: $name" }
        }
        check(Files.exists(world.resolve("datapacks/regen-sentinel.txt"))) { "Datapacks were deleted" }
        check(!Files.exists(world.resolve(WorldReset.PENDING_FILE))) { "Pending request was not consumed" }
        val saved = NbtIo.readCompressed(world.resolve(WorldReset.GENERATION_FILE), NbtAccounter.unlimitedHeap())
        check(saved.getCompoundOrEmpty("data").getLongOr("seed", -1) == seed) { "New seed was not saved to disk" }
    }
}
