package dev.screch.regen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;

public final class SingleplayerRegenerationTest implements ClientModInitializer {
    private IntegratedServer server;
    private ClientLevel previousLevel;
    private ClientPacketListener connection;
    private CompletableFuture<Void> check;
    private int ticks;
    private int startedAt;
    private int stage;
    private final long seed = SeedResolver.parse("singleplayer immediate seed with spaces");

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            client.options.pauseOnLostFocus = false;
            GLFW.glfwHideWindow(client.getWindow().handle());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception | AssertionError failure) {
                RegenMod.LOGGER.error("REGEN SINGLEPLAYER INTEGRATION TEST FAILED", failure);
                client.stop();
                throw new IllegalStateException("Singleplayer integration test failed", failure);
            }
        });
    }

    private void tick(Minecraft client) {
        if (++ticks > 1800) {
            throw new AssertionError("Timed out waiting for the singleplayer test");
        }
        if (stage == 0 && client.player != null && client.level != null && client.getSingleplayerServer() != null
            && client.level.getChunkSource().getLoadedChunksCount() > 0) {
            server = client.getSingleplayerServer();
            previousLevel = client.level;
            connection = client.getConnection();
            var uuid = client.player.getUUID();
            check = server.submit(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                player.getInventory().add(new ItemStack(Items.DIAMOND, 32));
                player.giveExperiencePoints(100);
                server.getWorldData().setAllowCommands(true);
                server.getPlayerList().setAllowCommandsForAllPlayers(true);
                server.getGlobalGameRules().set(GameRules.REDUCED_DEBUG_INFO, true, server);
                server.getGlobalGameRules().set(GameRules.IMMEDIATE_RESPAWN, true, server);
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                    "regen singleplayer immediate seed with spaces");
            });
            startedAt = ticks;
            stage = 1;
        } else if (stage == 1 && check.isDone() && ticks - startedAt > 60 && client.level != previousLevel
            && client.player != null && client.level.getChunkSource().getLoadedChunksCount() > 0) {
            check.join();
            require(client.getSingleplayerServer() == server, "Singleplayer server was restarted");
            require(client.getConnection() == connection, "Local player reconnected");
            require(client.player.getInventory().isEmpty(), "Client inventory still contains old items");
            require(client.player.totalExperience == 0, "Client experience was retained");
            require(!client.player.isReducedDebugInfo(), "Client retained the old debug game rule");
            require(client.player.shouldShowDeathScreen(), "Client retained the old respawn game rule");
            var uuid = client.player.getUUID();
            check = server.submit(() -> {
                require(server.isRunning(), "Integrated server stopped");
                require(server.getWorldGenSettings().options().seed() == seed, "Integrated server has the old seed");
                var player = server.getPlayerList().getPlayer(uuid);
                require(player != null && player.getInventory().isEmpty(), "Server player inventory was retained");
                require(player.level() == server.overworld(), "Player is outside the new overworld");
            });
            stage = 2;
        } else if (stage == 2 && check.isDone()) {
            check.join();
            RegenMod.LOGGER.info("REGEN SINGLEPLAYER INTEGRATION TEST PASSED: integrated server and local connection retained; client world cache, inventory and experience reset; fresh chunks received");
            stage = 3;
            client.stop();
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
