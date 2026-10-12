package dev.screch.regen

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

class RegenMod : ModInitializer {
    override fun onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register { server -> (server as RegenServer).regenRunPending() }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("regen")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes { regenerate(it.source, SeedResolver.random()) }
                .then(Commands.argument("seed", StringArgumentType.greedyString())
                    .executes { regenerateWithSeed(it.source, StringArgumentType.getString(it, "seed")) }))
        }
    }

    private fun regenerateWithSeed(source: CommandSourceStack, seed: String): Int = try {
        regenerate(source, SeedResolver.parse(seed))
    } catch (failure: IllegalArgumentException) {
        source.sendFailure(Component.literal(failure.message ?: "Invalid seed"))
        0
    }

    private fun regenerate(source: CommandSourceStack, seed: Long): Int {
        if (!(source.server as RegenServer).regenQueue(seed)) {
            source.sendFailure(Component.literal("This world is already regenerating"))
            return 0
        }
        source.sendSuccess({ Component.literal("Regenerating the world with seed $seed") }, true)
        logger.info("{} requested immediate regeneration with seed {}", source.textName, seed)
        return 1
    }

    companion object {
        val logger = LoggerFactory.getLogger("regen")
    }
}
