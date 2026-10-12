package dev.screch.regen;

import net.minecraft.server.level.ServerPlayer;
import java.util.List;

public interface RegenPlayerList {
    List<ServerPlayer> regen$detachPlayers();
    void regen$restorePlayers(List<ServerPlayer> players);
}
