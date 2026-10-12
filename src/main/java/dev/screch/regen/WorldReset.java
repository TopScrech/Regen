package dev.screch.regen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public final class WorldReset {
    public static final String PENDING_FILE = ".regen-pending.dat";
    public static final Path GENERATION_FILE = Path.of("data", "minecraft", "world_gen_settings.dat");
    private static final Set<String> RETAINED_FILES = Set.of(
        PENDING_FILE, "session.lock", "datapacks", "resourcepacks"
    );

    private WorldReset() {}

    public static void prepare(Path world, CompoundTag level, CompoundTag generation, long seed) throws IOException {
        validateWorld(world);
        Path pending = world.resolve(PENDING_FILE);
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("A regeneration is already pending for this world");
        }

        CompoundTag freshLevel = level.copy();
        freshLevel.remove("spawn");
        freshLevel.remove("Player");
        freshLevel.putLong("Time", 0);
        freshLevel.putBoolean("initialized", false);

        CompoundTag freshGeneration = generation.copy();
        freshGeneration.getCompound("data")
            .orElseThrow(() -> new IOException("Missing world generation data"))
            .putLong("seed", seed);

        CompoundTag plan = new CompoundTag();
        plan.putInt("format", 1);
        plan.put("level", freshLevel);
        plan.put("generation", freshGeneration);
        validatePlan(plan);
        writeAtomic(plan, pending);
    }

    // Only call while holding Minecraft's exclusive world lock, before loading world data
    public static boolean applyPending(Path world) throws IOException {
        Path pending = world.resolve(PENDING_FILE);
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        validateWorld(world);
        if (Files.isSymbolicLink(pending)) {
            throw new IOException("The regeneration request cannot be a symbolic link");
        }
        CompoundTag plan = NbtIo.readCompressed(pending, NbtAccounter.create(64L * 1024 * 1024));
        validatePlan(plan);

        try (var children = Files.newDirectoryStream(world)) {
            for (Path child : children) {
                if (!RETAINED_FILES.contains(child.getFileName().toString())) {
                    deleteTree(child);
                }
            }
        }

        CompoundTag levelRoot = new CompoundTag();
        levelRoot.put("Data", plan.getCompoundOrEmpty("level"));
        writeAtomic(levelRoot, world.resolve("level.dat"));
        Path generationPath = world.resolve(GENERATION_FILE);
        Files.createDirectories(generationPath.getParent());
        writeAtomic(plan.getCompoundOrEmpty("generation"), generationPath);
        // Leave the request intact on failure so an interrupted reset can be retried
        Files.delete(pending);
        return true;
    }

    private static void validateWorld(Path world) throws IOException {
        if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The world must be a real directory");
        }
    }

    private static void validatePlan(CompoundTag plan) throws IOException {
        CompoundTag level = plan.getCompound("level")
            .orElseThrow(() -> new IOException("Missing level settings in regeneration request"));
        CompoundTag generation = plan.getCompound("generation")
            .flatMap(tag -> tag.getCompound("data"))
            .orElseThrow(() -> new IOException("Missing generation settings in regeneration request"));
        if (plan.getIntOr("format", 0) != 1 || level.getString("LevelName").isEmpty()
            || generation.getLong("seed").isEmpty() || generation.getCompound("dimensions").isEmpty()) {
            throw new IOException("Invalid regeneration request");
        }
    }

    private static void writeAtomic(CompoundTag tag, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".regen-", ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        // File trees do not follow symbolic links, including links to other worlds
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
