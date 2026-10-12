package dev.screch.regen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldResetTest {
    @TempDir
    Path temporary;

    @Test
    void resetDeletedAllDimensionsAndPlayerProgressButKeptConfiguration() throws IOException {
        Path world = Files.createDirectory(temporary.resolve("world"));
        for (String file : new String[] {
            "region/r.0.0.mca", "DIM-1/region/r.0.0.mca", "DIM1/region/r.0.0.mca",
            "dimensions/minecraft/overworld/region/r.0.0.mca", "dimensions/minecraft/the_nether/entities/r.0.0.mca",
            "dimensions/minecraft/the_end/poi/r.0.0.mca", "dimensions/custom/moon/region/r.0.0.mca",
            "players/data/player.dat", "players/advancements/player.json", "players/stats/player.json",
            "data/minecraft/scoreboard.dat", "data/minecraft/forced_chunks.dat", "level.dat_old", "icon.png",
            "datapacks/custom/pack.mcmeta", "resourcepacks/resources.zip", "session.lock"
        }) {
            Path path = world.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "old world");
        }
        CompoundTag level = level();
        level.putLong("Time", 9000);
        level.put("spawn", new CompoundTag());
        level.put("Player", new CompoundTag());
        level.putBoolean("initialized", true);
        WorldReset.prepare(world, level, generation(), 0);
        assertTrue(Files.exists(world.resolve("players/data/player.dat")), "Queueing must not delete an active world");

        assertTrue(WorldReset.applyPending(world));
        assertFalse(Files.exists(world.resolve("region")));
        assertFalse(Files.exists(world.resolve("DIM-1")));
        assertFalse(Files.exists(world.resolve("DIM1")));
        assertFalse(Files.exists(world.resolve("dimensions")));
        assertFalse(Files.exists(world.resolve("players")));
        assertFalse(Files.exists(world.resolve("data/minecraft/scoreboard.dat")));
        assertFalse(Files.exists(world.resolve("level.dat_old")));
        assertTrue(Files.exists(world.resolve("datapacks/custom/pack.mcmeta")));
        assertTrue(Files.exists(world.resolve("resourcepacks/resources.zip")));
        assertEquals("old world", Files.readString(world.resolve("session.lock")));
        CompoundTag fresh = read(world.resolve("level.dat")).getCompoundOrEmpty("Data");
        assertEquals("My world", fresh.getStringOr("LevelName", ""));
        assertEquals(1, fresh.getIntOr("GameType", -1));
        assertFalse(fresh.getBooleanOr("initialized", true));
        assertFalse(fresh.contains("spawn"));
        assertFalse(fresh.contains("Player"));
        assertEquals(0, fresh.getLongOr("Time", -1));
        CompoundTag settings = read(world.resolve(WorldReset.GENERATION_FILE)).getCompoundOrEmpty("data");
        assertEquals(0, settings.getLongOr("seed", -1));
        assertTrue(settings.getBooleanOr("generate_structures", false));
        assertTrue(settings.getCompoundOrEmpty("dimensions").contains("minecraft:overworld"));
        assertFalse(Files.exists(world.resolve(WorldReset.PENDING_FILE)));
        assertFalse(WorldReset.applyPending(world), "Opening again must not reset a second time");
    }

    @Test
    void invalidRequestDidNotDeleteWorldData() throws IOException {
        Path world = Files.createDirectory(temporary.resolve("world"));
        Files.writeString(world.resolve("keep.txt"), "important");
        NbtIo.writeCompressed(new CompoundTag(), world.resolve(WorldReset.PENDING_FILE));
        assertThrows(IOException.class, () -> WorldReset.applyPending(world));
        assertEquals("important", Files.readString(world.resolve("keep.txt")));
        assertTrue(Files.exists(world.resolve(WorldReset.PENDING_FILE)));
    }

    @Test
    void duplicateRequestDidNotReplaceOriginalSeed() throws IOException {
        Path world = Files.createDirectory(temporary.resolve("world"));
        WorldReset.prepare(world, level(), generation(), 42);
        assertThrows(IOException.class, () -> WorldReset.prepare(world, level(), generation(), 99));
        WorldReset.applyPending(world);
        assertEquals(42, read(world.resolve(WorldReset.GENERATION_FILE)).getCompoundOrEmpty("data").getLongOr("seed", 0));
    }

    @Test
    void deletionDidNotFollowLinksOutsideTheWorld() throws IOException {
        Path world = Files.createDirectory(temporary.resolve("world"));
        Path outside = Files.createDirectory(temporary.resolve("other-world"));
        Files.writeString(outside.resolve("region.mca"), "keep");
        Files.createSymbolicLink(world.resolve("dimensions"), outside);
        WorldReset.prepare(world, level(), generation(), 42);
        WorldReset.applyPending(world);
        assertEquals("keep", Files.readString(outside.resolve("region.mca")));
    }

    @Test
    void linkedWorldWasRejected() throws IOException {
        Path world = Files.createDirectory(temporary.resolve("world"));
        Path link = Files.createSymbolicLink(temporary.resolve("linked-world"), world);
        assertThrows(IOException.class, () -> WorldReset.prepare(link, level(), generation(), 42));
    }

    private CompoundTag level() {
        CompoundTag level = new CompoundTag();
        level.putString("LevelName", "My world");
        level.putInt("GameType", 1);
        return level;
    }

    private CompoundTag generation() {
        CompoundTag dimensions = new CompoundTag();
        dimensions.put("minecraft:overworld", new CompoundTag());
        CompoundTag data = new CompoundTag();
        data.putLong("seed", 123);
        data.putBoolean("generate_structures", true);
        data.put("dimensions", dimensions);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", 1);
        return root;
    }

    private CompoundTag read(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }
}
