package dev.screch.regen;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class RegenMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("regen");

    @Override
    public void onInitialize() {
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
        MinecraftServer server = source.getServer();
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        try {
            var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
            CompoundTag generation = new CompoundTag();
            generation.put("data", WorldGenSettings.CODEC.encodeStart(ops, server.getWorldGenSettings()).getOrThrow());
            NbtUtils.addCurrentDataVersion(generation);
            var profile = server.getSingleplayerProfile();
            CompoundTag level = server.getWorldData().createTag(profile == null ? null : profile.id());
            WorldReset.prepare(world, level, generation, seed);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Could not queue regeneration for {}", world, failure);
            source.sendFailure(Component.literal("Could not regenerate world: " + failure.getMessage()));
            return 0;
        }

        String nextStep = server.isDedicatedServer() ? "Restart the server" : "Reopen this world";
        Component message = Component.literal("World reset queued with seed " + seed + " — " + nextStep + " to regenerate");
        source.sendSuccess(() -> message, true);
        LOGGER.info("{} queued regeneration for {} with seed {}", source.getTextName(), world, seed);
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            player.connection.disconnect(message);
        }
        server.halt(false);
        return 1;
    }
}
