# Regen

Fabric mod for Minecraft **26.2**, for dedicated servers and singleplayer

## Install

Use Java 25 or newer, Fabric Loader 0.19.5 or newer, and Fabric API for Minecraft 26.2

Place `regen-1.1.0.jar` in `mods` on the dedicated server, or in your Minecraft instance for singleplayer

## Commands

- `/regen` — reset the current world with a random 64-bit seed
- `/regen 123456789` — reset with a numeric seed, including zero or negative numbers
- `/regen my seed with spaces` — reset with a text seed; quotes are unnecessary

Text seeds use Minecraft's Java string hash, with leading and trailing whitespace trimmed
All text after `/regen ` belongs to the seed, including spaces between words

The command requires owner permissions (operator level 4 on a dedicated server, or cheats enabled in singleplayer)

## Reset behavior

Running the command starts regeneration at the end of the current server tick
The server stays running and players stay connected, then respawn in the new overworld with fresh inventories and progress
This also works in singleplayer without reopening the save
Gameplay pauses while the old worlds finish their writes and the new spawn is generated

The mod resets the entire save, including all vanilla and custom dimensions, chunks, entities, player inventories and positions, advancements, statistics, maps, scoreboards, game rules, and other saved progress
The world name, game mode, difficulty settings, generation options, dimension definitions, datapacks, and resourcepacks are retained
The new seed is written to Minecraft 26.2's `data/minecraft/world_gen_settings.dat`
`server.properties` is unchanged; the saved world's new seed takes precedence

Deletion is permanent and no automatic backup is created

The reset closes every old dimension and its storage before deleting files, while holding Minecraft's exclusive world lock
It then rebuilds world generation and saved-data caches, recreates players, and refreshes client chunks, biomes, abilities, recipes, advancements, statistics, and game-rule flags
An interrupted deletion retains its request and retries on the next world access
If an I/O or world-generation error prevents a rebuild after the old worlds have been closed, the server stops and logs the error

## Build

```sh
./gradlew build
```

The installable JAR is `build/libs/regen-1.1.0.jar`
Tests use temporary worlds to verify seed parsing, player and dimension deletion, retained settings, duplicate requests, corrupt requests, and symbolic link handling

Run the live server test, then the singleplayer client test:

```sh
./gradlew -PregenIntegrationTest runServer
./gradlew -PregenIntegrationTest runClient
```

These tests create disposable worlds under `build` and exit automatically
The server test checks text, random, and zero seeds with two test player connections in different dimensions
The singleplayer test uses a hidden Minecraft development client and checks that the actual local connection and integrated server survive regeneration and receive fresh chunks
The test mod is excluded from the installable JAR
