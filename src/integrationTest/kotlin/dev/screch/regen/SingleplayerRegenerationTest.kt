package dev.screch.regen

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.server.IntegratedServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.gamerules.GameRules
import org.lwjgl.glfw.GLFW
import java.util.concurrent.CompletableFuture

class SingleplayerRegenerationTest : ClientModInitializer {
    private lateinit var server: IntegratedServer
    private lateinit var previousLevel: ClientLevel
    private lateinit var connection: ClientPacketListener
    private lateinit var pendingCheck: CompletableFuture<Void>
    private var ticks = 0
    private var startedAt = 0
    private var stage = 0
    private val seed = SeedResolver.parse("singleplayer immediate seed with spaces")

    override fun onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register { client ->
            client.options.pauseOnLostFocus = false
            GLFW.glfwHideWindow(client.window.handle())
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            try {
                tick(client)
            } catch (failure: Throwable) {
                RegenMod.logger.error("REGEN SINGLEPLAYER INTEGRATION TEST FAILED", failure)
                client.stop()
                throw IllegalStateException("Singleplayer integration test failed", failure)
            }
        }
    }

    private fun tick(client: Minecraft) {
        check(++ticks <= 1800) { "Timed out waiting for the singleplayer test" }
        val clientPlayer = client.player
        val clientLevel = client.level
        if (stage == 0 && clientPlayer != null && clientLevel != null && client.singleplayerServer != null &&
            clientLevel.chunkSource.loadedChunksCount > 0) {
            server = requireNotNull(client.singleplayerServer)
            previousLevel = clientLevel
            connection = requireNotNull(client.connection)
            val uuid = clientPlayer.uuid
            pendingCheck = server.submit(Runnable {
                val player = requireNotNull(server.playerList.getPlayer(uuid))
                player.inventory.add(ItemStack(Items.DIAMOND, 32))
                player.giveExperiencePoints(100)
                server.worldData.setAllowCommands(true)
                server.playerList.setAllowCommandsForAllPlayers(true)
                server.overworld().gameRules.set(GameRules.REDUCED_DEBUG_INFO, true, server)
                server.overworld().gameRules.set(GameRules.IMMEDIATE_RESPAWN, true, server)
                server.commands.performPrefixedCommand(player.createCommandSourceStack(), "regen singleplayer immediate seed with spaces")
            })
            startedAt = ticks
            stage = 1
        } else if (stage == 1 && pendingCheck.isDone && ticks - startedAt > 60 && clientLevel !== previousLevel &&
            clientPlayer != null && clientLevel != null && clientLevel.chunkSource.loadedChunksCount > 0) {
            pendingCheck.join()
            check(client.singleplayerServer === server) { "Singleplayer server was restarted" }
            check(client.connection === connection) { "Local player reconnected" }
            check(clientPlayer.inventory.isEmpty) { "Client inventory still contains old items" }
            check(clientPlayer.totalExperience == 0) { "Client experience was retained" }
            check(!clientPlayer.isReducedDebugInfo) { "Client retained the old debug game rule" }
            check(clientPlayer.shouldShowDeathScreen()) { "Client retained the old respawn game rule" }
            val uuid = clientPlayer.uuid
            pendingCheck = server.submit(Runnable {
                check(server.isRunning) { "Integrated server stopped" }
                check(server.worldGenSettings.options().seed() == seed) { "Integrated server has the old seed" }
                val player = requireNotNull(server.playerList.getPlayer(uuid))
                check(player.inventory.isEmpty) { "Server player inventory was retained" }
                check(player.level() === server.overworld()) { "Player is outside the new overworld" }
            })
            stage = 2
        } else if (stage == 2 && pendingCheck.isDone) {
            pendingCheck.join()
            RegenMod.logger.info("REGEN SINGLEPLAYER INTEGRATION TEST PASSED: integrated server and local connection retained; client world cache, inventory and experience reset; fresh chunks received")
            stage = 3
            client.stop()
        }
    }
}
