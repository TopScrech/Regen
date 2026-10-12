package dev.screch.regen;

import net.minecraft.server.level.ServerPlayer;

public interface RegenPlayerConnection {
    void regen$replacePlayer(ServerPlayer player);
}
