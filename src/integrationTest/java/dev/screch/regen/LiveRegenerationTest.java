package dev.screch.regen;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LiveRegenerationTest implements ModInitializer {
    private final List<RecordingConnection> connections = new ArrayList<>();
    private List<ServerPlayer> previousPlayers;
    private ServerLevel previousLevel;
    private long previousSeed;
    private int stage;
    private int ticks;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer()) {
                return;
            }
            try {
                setup(server);
            } catch (Exception failure) {
                throw new IllegalStateException("Integration fixture failed", failure);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!server.isDedicatedServer()) {
                return;
            }
            if (++ticks % 5 != 0) {
                return;
            }
            try {
                runStage(server);
            } catch (Exception | AssertionError failure) {
                RegenMod.LOGGER.error("REGEN INTEGRATION TEST FAILED", failure);
                server.halt(false);
                throw new IllegalStateException("Immediate regeneration integration test failed", failure);
            }
        });
    }

    private void setup(MinecraftServer server) throws Exception {
        for (int index = 0; index < 2; index++) {
            ServerLevel level = index == 0 ? server.overworld() : server.getLevel(Level.NETHER);
            var profile = new GameProfile(UUID.nameUUIDFromBytes(("regen-player-" + index).getBytes()), "RegenTest" + index);
            var player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            RecordingConnection connection = new RecordingConnection(index == 1);
            connections.add(connection);
            new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
            var spawn = server.overworld().getRespawnData().pos();
            player.snapTo(spawn.getX(), spawn.getY(), spawn.getZ(), 0, 0);
            player.getInventory().add(new ItemStack(Items.DIAMOND, 32));
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 12));
            player.giveExperiencePoints(100);
            player.getStats().setValue(player, Stats.CUSTOM.get(Stats.JUMP), 123);
            var advancement = server.getAdvancements().get(Identifier.withDefaultNamespace("story/root"));
            player.getAdvancements().award(advancement, advancement.value().criteria().keySet().iterator().next());
            server.getPlayerList().getPlayers().add(player);
            server.getPlayerList().getPlayersByUUID().put(player.getUUID(), player);
            level.addNewPlayer(player);
        }
        server.getScoreboard().addObjective("regen_old", ObjectiveCriteria.DUMMY, Component.literal("Old score"),
            ObjectiveCriteria.RenderType.INTEGER, false, null);
        server.getScoreboard().addPlayerTeam("regen_old_team");
        server.setWeatherParameters(0, 6000, true, true);
        var world = server.getWorldPath(LevelResource.ROOT);
        for (String name : List.of("region", "DIM-1", "DIM1", "players/data", "datapacks")) {
            var fixture = world.resolve(name).resolve("regen-sentinel.txt");
            Files.createDirectories(fixture.getParent());
            Files.writeString(fixture, "old-world");
        }
        RegenMod.LOGGER.info("Integration fixture added two players in different dimensions with TCP and memory connection transports");
    }

    private void runStage(MinecraftServer server) throws Exception {
        switch (stage++) {
            case 0 -> {
                snapshot(server);
                command(server, "regen immediate seed with spaces");
                command(server, "regen ignored duplicate seed");
            }
            case 1 -> {
                verify(server, SeedResolver.parse("immediate seed with spaces"));
                snapshot(server);
                command(server, "regen");
            }
            case 2 -> {
                long seed = server.getWorldGenSettings().options().seed();
                require(seed != previousSeed, "Random seed did not change");
                verify(server, seed);
                snapshot(server);
                command(server, "regen 0");
            }
            case 3 -> {
                verify(server, 0);
                RegenMod.LOGGER.info("REGEN INTEGRATION TEST PASSED: immediate text, random and zero seeds; two connected players; terrain, player progress and client packets reset");
                server.halt(false);
            }
            default -> {}
        }
    }

    private void snapshot(MinecraftServer server) {
        previousLevel = server.overworld();
        previousPlayers = List.copyOf(server.getPlayerList().getPlayers());
        previousSeed = server.getWorldGenSettings().options().seed();
        connections.forEach(connection -> connection.packets.clear());
    }

    private void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private void verify(MinecraftServer server, long seed) throws Exception {
        require(server.isRunning(), "Server stopped during regeneration");
        require(server.overworld() != previousLevel, "Old overworld was retained");
        require(server.getWorldGenSettings().options().seed() == seed, "Generation settings have the wrong seed");
        for (ServerLevel level : server.getAllLevels()) {
            require(level.getSeed() == seed, "Dimension has the old seed");
        }
        require(server.getPlayerList().getPlayers().size() == 2, "Player count changed");
        for (int index = 0; index < 2; index++) {
            var oldPlayer = previousPlayers.get(index);
            var player = server.getPlayerList().getPlayer(oldPlayer.getUUID());
            require(player != oldPlayer, "Player data was retained");
            require(player.level() == server.overworld(), "Player did not return to the new spawn");
            require(player.connection == oldPlayer.connection, "Player connection was replaced");
            require(player.connection.player == player, "Connection still references the old player");
            require(player.getInventory().isEmpty(), "Inventory was retained");
            require(player.getEnderChestInventory().isEmpty(), "Ender chest was retained");
            require(player.totalExperience == 0, "Experience was retained");
            require(player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)) == 0, "Statistics were retained");
            require(server.getPlayerList().loadPlayerData(player.nameAndId()).isPresent(), "New player data was not saved");
            var root = server.getAdvancements().get(Identifier.withDefaultNamespace("story/root"));
            require(!player.getAdvancements().getOrStartProgress(root).isDone(), "Advancements were retained");
            RecordingConnection connection = connections.get(index);
            require(!connection.disconnected, "Player disconnected");
            require(connection.packets.stream().filter(packet -> packet instanceof ClientboundRespawnPacket).count() == 2,
                "Client did not receive both world cache reset packets");
            require(connection.packets.stream().anyMatch(packet -> packet instanceof ClientboundUpdateAdvancementsPacket update && update.shouldReset()),
                "Client advancements were not cleared");
        }
        require(server.getScoreboard().getObjective("regen_old") == null, "Scoreboard was retained");
        require(server.getScoreboard().getPlayerTeam("regen_old_team") == null, "Team was retained");
        require(!server.getWeatherData().isRaining(), "Weather was retained");
        var world = server.getWorldPath(LevelResource.ROOT);
        for (String name : List.of("region", "DIM-1", "DIM1", "players/data")) {
            require(!Files.exists(world.resolve(name).resolve("regen-sentinel.txt")), "Old world data was retained: " + name);
        }
        require(Files.exists(world.resolve("datapacks/regen-sentinel.txt")), "Datapacks were deleted");
        require(!Files.exists(world.resolve(WorldReset.PENDING_FILE)), "Pending request was not consumed");
        var saved = NbtIo.readCompressed(world.resolve(WorldReset.GENERATION_FILE), NbtAccounter.unlimitedHeap());
        require(saved.getCompoundOrEmpty("data").getLongOr("seed", -1) == seed, "New seed was not saved to disk");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
