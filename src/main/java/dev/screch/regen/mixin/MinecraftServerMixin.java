package dev.screch.regen.mixin;

import dev.screch.regen.RegenMod;
import dev.screch.regen.RegenPlayerList;
import dev.screch.regen.RegenServer;
import dev.screch.regen.WorldReset;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.timers.TimerQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements RegenServer {
    @Shadow @Final private Map<ResourceKey<Level>, ServerLevel> levels;
    @Shadow @Final protected WorldData worldData;
    @Shadow @Final @Mutable private WorldGenSettings worldGenSettings;
    @Shadow @Final @Mutable private SavedDataStorage savedDataStorage;
    @Shadow @Final @Mutable private ServerScoreboard scoreboard;
    @Shadow @Final @Mutable private RandomSequences randomSequences;
    @Shadow @Final @Mutable private WeatherData weatherData;
    @Shadow @Final @Mutable private GameRules gameRules;
    @Shadow @Final @Mutable private ServerClockManager clockManager;
    @Shadow @Final @Mutable private CustomBossEvents customBossEvents;
    @Shadow @Final @Mutable private TimerQueue<MinecraftServer> scheduledEvents;
    @Shadow private long nextTickTimeNanos;
    @Shadow private long lastTickNanos;
    @Shadow protected abstract void createLevels();
    @Shadow protected abstract void waitUntilNextTick();
    @Shadow protected abstract void updateEffectiveRespawnData();

    @Unique private Long regen$requestedSeed;
    @Unique private volatile boolean regen$rebuilding;

    @Override
    public boolean regen$queue(long seed) {
        if (regen$requestedSeed != null || regen$rebuilding) {
            return false;
        }
        regen$requestedSeed = seed;
        return true;
    }

    @Override
    public boolean regen$isRebuilding() {
        return regen$rebuilding;
    }

    @Override
    public void regen$runPending() {
        if (regen$requestedSeed == null || regen$rebuilding) {
            return;
        }
        long seed = regen$requestedSeed;
        regen$requestedSeed = null;
        MinecraftServer server = (MinecraftServer) (Object) this;
        var world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        try {
            var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
            CompoundTag generation = new CompoundTag();
            generation.put("data", WorldGenSettings.CODEC.encodeStart(ops, worldGenSettings).getOrThrow());
            NbtUtils.addCurrentDataVersion(generation);
            var profile = server.getSingleplayerProfile();
            WorldReset.prepare(world, worldData.createTag(profile == null ? null : profile.id()), generation, seed);
        } catch (IOException | RuntimeException failure) {
            RegenMod.LOGGER.error("Could not prepare regeneration", failure);
            server.getPlayerList().broadcastSystemMessage(Component.literal("Could not regenerate: " + failure.getMessage()), false);
            return;
        }

        regen$rebuilding = true;
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        try {
            // Finish the tick before replacing levels, and keep the world lock throughout
            RegenPlayerList playerList = (RegenPlayerList) server.getPlayerList();
            for (var objective : List.copyOf(scoreboard.getObjectives())) {
                scoreboard.removeObjective(objective);
            }
            for (var team : List.copyOf(scoreboard.getPlayerTeams())) {
                scoreboard.removePlayerTeam(team);
            }
            playerList.regen$detachPlayers();

            while (levels.values().stream().anyMatch(level -> level.getChunkSource().chunkMap.hasWork())) {
                nextTickTimeNanos = Util.getNanos() + 1_000_000;
                for (ServerLevel level : levels.values()) {
                    level.getChunkSource().deactivateTicketsOnClosing();
                    level.getChunkSource().tick(() -> true, false);
                }
                waitUntilNextTick();
            }
            server.saveAllChunks(true, true, true);
            for (ServerLevel level : levels.values()) {
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
                level.close();
            }
            savedDataStorage.close();
            levels.clear();
            WorldReset.applyPending(world);
            Files.createDirectories(server.getWorldPath(LevelResource.PLAYER_DATA_DIR));

            worldGenSettings = new WorldGenSettings(worldGenSettings.options().withSeed(OptionalLong.of(seed)), worldGenSettings.dimensions());
            savedDataStorage = new SavedDataStorage(server.getWorldPath(LevelResource.DATA), server.getFixerUpper(), server.registryAccess());
            savedDataStorage.set(WorldGenSettings.TYPE, worldGenSettings);
            randomSequences = savedDataStorage.computeIfAbsent(RandomSequences.TYPE);
            weatherData = savedDataStorage.computeIfAbsent(WeatherData.TYPE);
            gameRules = new GameRules(worldData.enabledFeatures(), savedDataStorage.computeIfAbsent(GameRuleMap.TYPE));
            clockManager = savedDataStorage.computeIfAbsent(ServerClockManager.TYPE);
            clockManager.init(server);
            customBossEvents = savedDataStorage.computeIfAbsent(CustomBossEvents.TYPE);
            scheduledEvents = savedDataStorage.computeIfAbsent(TimerQueue.TYPE);
            scoreboard = new ServerScoreboard(server);
            server.getStructureManager().onResourceManagerReload(server.getResourceManager());
            worldData.overworldData().setGameTime(0);
            worldData.overworldData().setInitialized(false);
            worldData.overworldData().setSpawn(LevelData.RespawnData.DEFAULT);
            createLevels();
            updateEffectiveRespawnData();
            server.updateMobSpawningFlags();
            playerList.regen$restorePlayers(players);
            server.saveEverything(true, true, true);
            server.invalidateStatus();
            server.getPlayerList().broadcastSystemMessage(Component.literal("World regenerated with seed " + seed), false);
            RegenMod.LOGGER.info("World regenerated immediately with seed {} — {} players stayed connected", seed, server.getPlayerCount());
        } catch (IOException | RuntimeException failure) {
            RegenMod.LOGGER.error("World regeneration failed", failure);
            for (ServerPlayer player : players) {
                player.connection.disconnect(Component.literal("World regeneration failed — check the server log"));
            }
            // Closed worlds cannot safely resume ticking after a failed rebuild
            server.halt(false);
        } finally {
            nextTickTimeNanos = Util.getNanos();
            lastTickNanos = nextTickTimeNanos;
            regen$rebuilding = false;
        }
    }

    @Inject(method = "getNextTickTime", at = @At("HEAD"), cancellable = true)
    private void regen$allowRegenerationTime(CallbackInfoReturnable<Long> callback) {
        if (regen$rebuilding) {
            callback.setReturnValue(Util.getNanos());
        }
    }
}
