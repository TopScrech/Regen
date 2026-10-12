package dev.screch.regen

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

import org.junit.jupiter.api.Assertions.*

class WorldResetTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun resetDeletedAllDimensionsAndPlayerProgressButKeptConfiguration() {
        val world = Files.createDirectory(temporary.resolve("world"))
        for (file in listOf(
            "region/r.0.0.mca", "DIM-1/region/r.0.0.mca", "DIM1/region/r.0.0.mca",
            "dimensions/minecraft/overworld/region/r.0.0.mca", "dimensions/minecraft/the_nether/entities/r.0.0.mca",
            "dimensions/minecraft/the_end/poi/r.0.0.mca", "dimensions/custom/moon/region/r.0.0.mca",
            "players/data/player.dat", "players/advancements/player.json", "players/stats/player.json",
            "data/minecraft/scoreboard.dat", "data/minecraft/forced_chunks.dat", "level.dat_old", "icon.png",
            "datapacks/custom/pack.mcmeta", "resourcepacks/resources.zip", "session.lock"
        )) {
            val path = world.resolve(file)
            Files.createDirectories(path.parent)
            Files.writeString(path, "old world")
        }
        val level = level()
        level.putLong("Time", 9000)
        level.put("spawn", CompoundTag())
        level.put("Player", CompoundTag())
        level.putBoolean("initialized", true)
        WorldReset.prepare(world, level, generation(), 0)
        assertTrue(Files.exists(world.resolve("players/data/player.dat")), "Queueing must not delete an active world")

        assertTrue(WorldReset.applyPending(world))
        assertFalse(Files.exists(world.resolve("region")))
        assertFalse(Files.exists(world.resolve("DIM-1")))
        assertFalse(Files.exists(world.resolve("DIM1")))
        assertFalse(Files.exists(world.resolve("dimensions")))
        assertFalse(Files.exists(world.resolve("players")))
        assertFalse(Files.exists(world.resolve("data/minecraft/scoreboard.dat")))
        assertFalse(Files.exists(world.resolve("level.dat_old")))
        assertTrue(Files.exists(world.resolve("datapacks/custom/pack.mcmeta")))
        assertTrue(Files.exists(world.resolve("resourcepacks/resources.zip")))
        assertEquals("old world", Files.readString(world.resolve("session.lock")))
        val fresh = read(world.resolve("level.dat")).getCompoundOrEmpty("Data")
        assertEquals("My world", fresh.getStringOr("LevelName", ""))
        assertEquals(1, fresh.getIntOr("GameType", -1))
        assertFalse(fresh.getBooleanOr("initialized", true))
        assertFalse(fresh.contains("spawn"))
        assertFalse(fresh.contains("Player"))
        assertEquals(0L, fresh.getLongOr("Time", -1))
        val settings = read(world.resolve(WorldReset.GENERATION_FILE)).getCompoundOrEmpty("data")
        assertEquals(0L, settings.getLongOr("seed", -1))
        assertTrue(settings.getBooleanOr("generate_structures", false))
        assertTrue(settings.getCompoundOrEmpty("dimensions").contains("minecraft:overworld"))
        assertFalse(Files.exists(world.resolve(WorldReset.PENDING_FILE)))
        assertFalse(WorldReset.applyPending(world), "Opening again must not reset a second time")
    }

    @Test
    fun invalidRequestDidNotDeleteWorldData() {
        val world = Files.createDirectory(temporary.resolve("world"))
        Files.writeString(world.resolve("keep.txt"), "important")
        NbtIo.writeCompressed(CompoundTag(), world.resolve(WorldReset.PENDING_FILE))
        assertThrows(IOException::class.java) { WorldReset.applyPending(world) }
        assertEquals("important", Files.readString(world.resolve("keep.txt")))
        assertTrue(Files.exists(world.resolve(WorldReset.PENDING_FILE)))
    }

    @Test
    fun duplicateRequestDidNotReplaceOriginalSeed() {
        val world = Files.createDirectory(temporary.resolve("world"))
        WorldReset.prepare(world, level(), generation(), 42)
        assertThrows(IOException::class.java) { WorldReset.prepare(world, level(), generation(), 99) }
        WorldReset.applyPending(world)
        assertEquals(42L, read(world.resolve(WorldReset.GENERATION_FILE)).getCompoundOrEmpty("data").getLongOr("seed", 0))
    }

    @Test
    fun deletionDidNotFollowLinksOutsideTheWorld() {
        val world = Files.createDirectory(temporary.resolve("world"))
        val outside = Files.createDirectory(temporary.resolve("other-world"))
        Files.writeString(outside.resolve("region.mca"), "keep")
        Files.createSymbolicLink(world.resolve("dimensions"), outside)
        WorldReset.prepare(world, level(), generation(), 42)
        WorldReset.applyPending(world)
        assertEquals("keep", Files.readString(outside.resolve("region.mca")))
    }

    @Test
    fun linkedWorldWasRejected() {
        val world = Files.createDirectory(temporary.resolve("world"))
        val link = Files.createSymbolicLink(temporary.resolve("linked-world"), world)
        assertThrows(IOException::class.java) { WorldReset.prepare(link, level(), generation(), 42) }
    }

    private fun level(): CompoundTag {
        val level = CompoundTag()
        level.putString("LevelName", "My world")
        level.putInt("GameType", 1)
        return level
    }

    private fun generation(): CompoundTag {
        val dimensions = CompoundTag()
        dimensions.put("minecraft:overworld", CompoundTag())
        val data = CompoundTag()
        data.putLong("seed", 123)
        data.putBoolean("generate_structures", true)
        data.put("dimensions", dimensions)
        val root = CompoundTag()
        root.put("data", data)
        root.putInt("DataVersion", 1)
        return root
    }

    private fun read(file: Path): CompoundTag {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
    }
}
