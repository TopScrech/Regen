package dev.screch.regen;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class RegenMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("regen");

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> ((RegenServer) server).regen$runPending());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("regen")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> regenerate(context.getSource(), SeedResolver.random()))
                .then(Commands.argument("seed", StringArgumentType.greedyString())
                    .executes(context -> regenerateWithSeed(context.getSource(),
                        StringArgumentType.getString(context, "seed"))))));
    }

    private static int regenerateWithSeed(CommandSourceStack source, String seed) {
        try {
            return regenerate(source, SeedResolver.parse(seed));
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal(failure.getMessage()));
            return 0;
        }
    }

    private static int regenerate(CommandSourceStack source, long seed) {
        if (!((RegenServer) source.getServer()).regen$queue(seed)) {
            source.sendFailure(Component.literal("This world is already regenerating"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Regenerating the world with seed " + seed), true);
        LOGGER.info("{} requested immediate regeneration with seed {}", source.getTextName(), seed);
        return 1;
    }
}
