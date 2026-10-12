package dev.screch.regen.mixin;

import dev.screch.regen.RegenPlayerConnection;
import dev.screch.regen.RegenPlayerList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.EnumSet;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin implements RegenPlayerList {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private List<ServerPlayer> players;
    @Shadow @Final private Map<UUID, ServerPlayer> playersByUUID;
    @Shadow @Final private Map<UUID, ServerStatsCounter> stats;
    @Shadow @Final private Map<UUID, PlayerAdvancements> advancements;
    @Shadow public abstract void sendLevelInfo(ServerPlayer player, ServerLevel level);
    @Shadow public abstract void sendPlayerPermissionLevel(ServerPlayer player);

    @Override
    public List<ServerPlayer> regen$detachPlayers() {
        List<ServerPlayer> previous = List.copyOf(players);
        for (ServerPlayer player : previous) {
            var clearedStats = new Object2IntOpenHashMap<Stat<?>>();
            ((StatsCounterAccessor) player.getStats()).regen$getStats().keySet().forEach(stat -> clearedStats.put(stat, 0));
            player.connection.send(new ClientboundAwardStatsPacket(clearedStats));
            player.closeContainer();
            player.stopRiding();
            player.getAdvancements().clearTriggers();
            server.getCustomBossEvents().onPlayerDisconnect(player);
            player.level().removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION);
        }
        players.clear();
        playersByUUID.clear();
        stats.clear();
        advancements.clear();
        return previous;
    }

    @Override
    public void regen$restorePlayers(List<ServerPlayer> previous) {
        ServerLevel level = server.overworld();
        for (ServerPlayer oldPlayer : previous) {
            if (!oldPlayer.connection.isAcceptingMessages()) {
                continue;
            }
            ServerPlayer player = new ServerPlayer(server, level, oldPlayer.getGameProfile(), oldPlayer.clientInformation());
            player.connection = oldPlayer.connection;
            player.setId(oldPlayer.getId());
            if (oldPlayer.getChatSession() != null) {
                player.setChatSession(oldPlayer.getChatSession());
            }
            var spawn = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
            player.snapTo(spawn.position().x, spawn.position().y, spawn.position().z, spawn.yRot(), spawn.xRot());
            ((RegenPlayerConnection) player.connection).regen$replacePlayer(player);

            CommonPlayerSpawnInfo info = player.createCommonSpawnInfo(level);
            // A dimension change makes vanilla clients discard their old chunk and biome caches
            CommonPlayerSpawnInfo transition = new CommonPlayerSpawnInfo(info.dimensionType(), Level.NETHER,
                info.seed(), info.gameType(), info.previousGameType(), info.isDebug(), info.isFlat(),
                info.lastDeathLocation(), info.portalCooldown(), info.seaLevel());
            player.connection.send(new ClientboundRespawnPacket(transition, (byte) 0));
            player.connection.send(new ClientboundRespawnPacket(info, (byte) 0));
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            player.connection.send(new ClientboundChangeDifficultyPacket(server.getWorldData().getDifficulty(), server.getWorldData().isDifficultyLocked()));
            player.connection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
            player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), player.getAttributes().getSyncableAttributes()));
            player.connection.send(new ClientboundEntityEventPacket(player,
                (byte) (server.getGlobalGameRules().get(GameRules.REDUCED_DEBUG_INFO) ? 22 : 23)));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN,
                server.getGlobalGameRules().get(GameRules.IMMEDIATE_RESPAWN) ? 1 : 0));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LIMITED_CRAFTING,
                server.getGlobalGameRules().get(GameRules.LIMITED_CRAFTING) ? 1 : 0));
            player.connection.send(new ClientboundSetHeldSlotPacket(0));
            player.connection.send(new ClientboundSetExperiencePacket(0, 0, 0));
            player.getRecipeBook().sendInitialRecipeBook(player);
            player.connection.send(new ClientboundUpdateAdvancementsPacket(true, List.of(), Set.of(), Map.of(), false));
            player.getAdvancements().flushDirty(player, false);
            sendLevelInfo(player, level);
            sendPlayerPermissionLevel(player);
            level.addRespawnedPlayer(player);
            players.add(player);
            playersByUUID.put(player.getUUID(), player);
            player.initInventoryMenu();
            player.setHealth(player.getHealth());
            server.getCustomBossEvents().onPlayerConnect(player);
        }
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), players));
    }
}
