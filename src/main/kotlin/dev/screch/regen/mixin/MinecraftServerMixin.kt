package dev.screch.regen.mixin

import dev.screch.regen.RegenMod
import dev.screch.regen.RegenPlayerList
import dev.screch.regen.RegenServer
import dev.screch.regen.WorldReset
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.ServerScoreboard
import net.minecraft.server.bossevents.CustomBossEvents
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.RandomSequences
import net.minecraft.world.clock.ServerClockManager
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.gamerules.GameRuleMap
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.level.saveddata.WeatherData
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.SavedDataStorage
import net.minecraft.world.level.storage.WorldData
import net.minecraft.world.level.timers.TimerQueue
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.nio.file.Files
import java.util.OptionalLong
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(MinecraftServer::class)
abstract class MinecraftServerMixin : RegenServer {
    @field:Shadow @field:Final private lateinit var levels: MutableMap<ResourceKey<Level>, ServerLevel>
    @field:Shadow @field:Final private lateinit var worldData: WorldData
    @field:Shadow @field:Final @field:Mutable private lateinit var worldGenSettings: WorldGenSettings
    @field:Shadow @field:Final @field:Mutable private lateinit var savedDataStorage: SavedDataStorage
    @field:Shadow @field:Final @field:Mutable private lateinit var scoreboard: ServerScoreboard
    @field:Shadow @field:Final @field:Mutable private lateinit var randomSequences: RandomSequences
    @field:Shadow @field:Final @field:Mutable private lateinit var weatherData: WeatherData
    @field:Shadow @field:Final @field:Mutable private lateinit var gameRules: GameRules
    @field:Shadow @field:Final @field:Mutable private lateinit var clockManager: ServerClockManager
    @field:Shadow @field:Final @field:Mutable private lateinit var customBossEvents: CustomBossEvents
    @field:Shadow @field:Final @field:Mutable private lateinit var scheduledEvents: TimerQueue<MinecraftServer>
    @Accessor("nextTickTimeNanos") protected abstract fun regenSetNextTickTimeNanos(value: Long)
    @Accessor("lastTickNanos") protected abstract fun regenSetLastTickNanos(value: Long)
    @Shadow protected abstract fun createLevels()
    @Shadow protected abstract fun waitUntilNextTick()
    @Shadow protected abstract fun updateEffectiveRespawnData()

    @field:Unique private var regenRequestedSeed: Long? = null
    @field:Unique @Volatile private var regenRebuilding = false

    override fun regenQueue(seed: Long): Boolean {
        if (regenRequestedSeed != null || regenRebuilding) return false
        regenRequestedSeed = seed
        return true
    }

    override fun regenIsRebuilding(): Boolean = regenRebuilding

    override fun regenRunPending() {
        val seed = regenRequestedSeed ?: return
        if (regenRebuilding) return
        regenRequestedSeed = null
        val server = this as Any as MinecraftServer
        val world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        try {
            val ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess())
            val generation = CompoundTag()
            generation.put("data", WorldGenSettings.CODEC.encodeStart(ops, worldGenSettings).orThrow)
            NbtUtils.addCurrentDataVersion(generation)
            WorldReset.prepare(world, worldData.createTag(server.singleplayerProfile?.id()), generation, seed)
        } catch (failure: Exception) {
            RegenMod.logger.error("Could not prepare regeneration", failure)
            server.playerList.broadcastSystemMessage(Component.literal("Could not regenerate: ${failure.message}"), false)
            return
        }

        regenRebuilding = true
        val players = server.playerList.players.toList()
        try {
            // Finish the tick before replacing levels, keeping the world lock throughout
            val playerList = server.playerList as RegenPlayerList
            for (objective in scoreboard.objectives.toList()) scoreboard.removeObjective(objective)
            for (team in scoreboard.playerTeams.toList()) scoreboard.removePlayerTeam(team)
            playerList.regenDetachPlayers()
            while (levels.values.any { it.chunkSource.chunkMap.hasWork() }) {
                regenSetNextTickTimeNanos(Util.getNanos() + 1_000_000)
                for (level in levels.values) {
                    level.chunkSource.deactivateTicketsOnClosing()
                    level.chunkSource.tick({ true }, false)
                }
                waitUntilNextTick()
            }
            server.saveAllChunks(true, true, true)
            for (level in levels.values) {
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level)
                level.close()
            }
            savedDataStorage.close()
            levels.clear()
            WorldReset.applyPending(world)
            Files.createDirectories(server.getWorldPath(LevelResource.PLAYER_DATA_DIR))
            worldGenSettings = WorldGenSettings(worldGenSettings.options().withSeed(OptionalLong.of(seed)), worldGenSettings.dimensions())
            savedDataStorage = SavedDataStorage(server.getWorldPath(LevelResource.DATA), server.fixerUpper, server.registryAccess())
            savedDataStorage.set(WorldGenSettings.TYPE, worldGenSettings)
            randomSequences = savedDataStorage.computeIfAbsent(RandomSequences.TYPE)
            weatherData = savedDataStorage.computeIfAbsent(WeatherData.TYPE)
            gameRules = GameRules(worldData.enabledFeatures(), savedDataStorage.computeIfAbsent(GameRuleMap.TYPE))
            clockManager = savedDataStorage.computeIfAbsent(ServerClockManager.TYPE)
            clockManager.init(server)
            customBossEvents = savedDataStorage.computeIfAbsent(CustomBossEvents.TYPE)
            scheduledEvents = savedDataStorage.computeIfAbsent(TimerQueue.TYPE)
            scoreboard = ServerScoreboard(server)
            server.structureManager.onResourceManagerReload(server.resourceManager)
            worldData.overworldData().setGameTime(0)
            worldData.overworldData().setInitialized(false)
            worldData.overworldData().setSpawn(LevelData.RespawnData.DEFAULT)
            createLevels()
            updateEffectiveRespawnData()
            server.updateMobSpawningFlags()
            playerList.regenRestorePlayers(players)
            server.saveEverything(true, true, true)
            server.invalidateStatus()
            server.playerList.broadcastSystemMessage(Component.literal("World regenerated with seed $seed"), false)
            RegenMod.logger.info("World regenerated immediately with seed {} — {} players stayed connected", seed, server.playerCount)
        } catch (failure: Exception) {
            RegenMod.logger.error("World regeneration failed", failure)
            for (player in players) player.connection.disconnect(Component.literal("World regeneration failed — check the server log"))
            // Closed worlds cannot safely resume ticking after a failed rebuild
            server.halt(false)
        } finally {
            val now = Util.getNanos()
            regenSetNextTickTimeNanos(now)
            regenSetLastTickNanos(now)
            regenRebuilding = false
        }
    }

    @Inject(method = ["getNextTickTime"], at = [At("HEAD")], cancellable = true)
    private fun regenAllowRegenerationTime(callback: CallbackInfoReturnable<Long>) {
        if (regenRebuilding) callback.returnValue = Util.getNanos()
    }
}
