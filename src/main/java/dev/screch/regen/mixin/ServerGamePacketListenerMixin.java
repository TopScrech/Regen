package dev.screch.regen.mixin;

import dev.screch.regen.RegenPlayerConnection;
import dev.screch.regen.RegenServer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin extends ServerCommonPacketListenerImpl implements RegenPlayerConnection {
    @Shadow public ServerPlayer player;
    @Shadow @Final @Mutable public PlayerChunkSender chunkSender;
    @Shadow public abstract void resetPosition();
    @Shadow public abstract void resetFlyingTicks();

    protected ServerGamePacketListenerMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Override
    public void regen$replacePlayer(ServerPlayer replacement) {
        player = replacement;
        chunkSender = new PlayerChunkSender(connection.isMemoryConnection());
        resetPosition();
        resetFlyingTicks();
    }

    @Inject(method = "shouldHandleMessage", at = @At("HEAD"), cancellable = true)
    private void regen$pauseWorldPackets(Packet<?> packet, CallbackInfoReturnable<Boolean> callback) {
        if (((RegenServer) server).regen$isRebuilding() && !(packet instanceof ServerboundKeepAlivePacket)) {
            callback.setReturnValue(false);
        }
    }
}
