# Regen

Fabric mod for Minecraft **26.2**, for dedicated servers and singleplayer

## Install

Use Java 25 or newer, Fabric Loader 0.19.5 or newer, and Fabric API for Minecraft 26.2

Place `regen-1.0.0.jar` in `mods` on the dedicated server, or in your Minecraft instance for singleplayer

## Commands

- `/regen` — reset the current world with a random 64-bit seed
- `/regen 123456789` — reset with a numeric seed, including zero or negative numbers
- `/regen my seed with spaces` — reset with a text seed; quotes are unnecessary

Text seeds use Minecraft's Java string hash, with leading and trailing whitespace trimmed
All text after `/regen ` belongs to the seed, including spaces between words

The command requires owner permissions (operator level 4 on a dedicated server, or cheats enabled in singleplayer)

## Reset behavior

Running the command immediately queues the reset, disconnects every player, and shuts down the world cleanly
Restart the dedicated server, or reopen the singleplayer world, to generate the new world
Use your host's restart-on-stop setting if you want the dedicated server to start again automatically

The mod resets the entire save, including all vanilla and custom dimensions, chunks, entities, player inventories and positions, advancements, statistics, maps, scoreboards, game rules, and other saved progress
The world name, game mode, difficulty settings, generation options, dimension definitions, datapacks, and resourcepacks are retained
The new seed is written to Minecraft 26.2's `data/minecraft/world_gen_settings.dat`
`server.properties` is unchanged; the saved world's new seed takes precedence

Deletion is permanent and no automatic backup is created

The reset runs when Minecraft next acquires exclusive access to the save, before it loads world data
In singleplayer this can also happen if you open the world's edit screen after queueing a reset
Loaded chunks cannot be saved over the regenerated world
An interrupted reset retains its request and retries on the next world access
Keep the mod installed until the reset completes

## Build

```sh
./gradlew build
```

The installable JAR is `build/libs/regen-1.0.0.jar`
Tests use temporary worlds to verify seed parsing, player and dimension deletion, retained settings, duplicate requests, corrupt requests, and symbolic link handling
