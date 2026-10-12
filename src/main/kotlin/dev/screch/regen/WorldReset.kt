package dev.screch.regen

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

object WorldReset {
    const val PENDING_FILE = ".regen-pending.dat"
    val GENERATION_FILE: Path = Path.of("data", "minecraft", "world_gen_settings.dat")
    private val retainedFiles = setOf(PENDING_FILE, "session.lock", "datapacks", "resourcepacks")

    fun prepare(world: Path, level: CompoundTag, generation: CompoundTag, seed: Long) {
        validateWorld(world)
        val pending = world.resolve(PENDING_FILE)
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("A regeneration is already pending for this world")
        }
        val freshLevel = level.copy()
        freshLevel.remove("spawn")
        freshLevel.remove("Player")
        freshLevel.putLong("Time", 0)
        freshLevel.putBoolean("initialized", false)
        val freshGeneration = generation.copy()
        freshGeneration.getCompound("data").orElseThrow { IOException("Missing world generation data") }
            .putLong("seed", seed)
        val plan = CompoundTag()
        plan.putInt("format", 1)
        plan.put("level", freshLevel)
        plan.put("generation", freshGeneration)
        validatePlan(plan)
        writeAtomic(plan, pending)
    }

    // Only call while holding Minecraft's exclusive world lock
    fun applyPending(world: Path): Boolean {
        val pending = world.resolve(PENDING_FILE)
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return false
        validateWorld(world)
        if (Files.isSymbolicLink(pending)) throw IOException("The regeneration request cannot be a symbolic link")
        val plan = NbtIo.readCompressed(pending, NbtAccounter.create(64L * 1024 * 1024))
        validatePlan(plan)
        Files.newDirectoryStream(world).use { children ->
            for (child in children) {
                if (child.fileName.toString() !in retainedFiles) deleteTree(child)
            }
        }
        val levelRoot = CompoundTag()
        levelRoot.put("Data", plan.getCompoundOrEmpty("level"))
        writeAtomic(levelRoot, world.resolve("level.dat"))
        val generationPath = world.resolve(GENERATION_FILE)
        Files.createDirectories(generationPath.parent)
        writeAtomic(plan.getCompoundOrEmpty("generation"), generationPath)
        // Keep the request on failure so an interrupted reset can be retried
        Files.delete(pending)
        return true
    }

    private fun validateWorld(world: Path) {
        if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("The world must be a real directory")
        }
    }

    private fun validatePlan(plan: CompoundTag) {
        val level = plan.getCompound("level").orElseThrow { IOException("Missing level settings in regeneration request") }
        val generation = plan.getCompound("generation").flatMap { it.getCompound("data") }
            .orElseThrow { IOException("Missing generation settings in regeneration request") }
        if (plan.getIntOr("format", 0) != 1 || level.getString("LevelName").isEmpty ||
            generation.getLong("seed").isEmpty || generation.getCompound("dimensions").isEmpty) {
            throw IOException("Invalid regeneration request")
        }
    }

    private fun writeAtomic(tag: CompoundTag, destination: Path) {
        val temporary = Files.createTempFile(destination.parent, ".regen-", ".tmp")
        try {
            NbtIo.writeCompressed(tag, temporary)
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deleteTree(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
