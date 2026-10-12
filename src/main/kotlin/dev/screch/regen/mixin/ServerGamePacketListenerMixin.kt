package dev.screch.regen.mixin

import dev.screch.regen.RegenPlayerConnection
import dev.screch.regen.RegenServer
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.*
import org.spongepowered.asm.mixin.*
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ServerGamePacketListenerImpl::class)
abstract class ServerGamePacketListenerMixin(server: MinecraftServer, connection: Connection, cookie: CommonListenerCookie) :
    ServerCommonPacketListenerImpl(server, connection, cookie), RegenPlayerConnection {
    @field:Shadow private lateinit var player: ServerPlayer
    @field:Shadow @field:Final @field:Mutable private lateinit var chunkSender: PlayerChunkSender
    @Shadow abstract fun resetPosition()
    @Shadow abstract fun resetFlyingTicks()

    override fun regenReplacePlayer(replacement: ServerPlayer) {
        player = replacement
        chunkSender = PlayerChunkSender(connection.isMemoryConnection)
        resetPosition()
        resetFlyingTicks()
    }

    @Inject(method = ["shouldHandleMessage"], at = [At("HEAD")], cancellable = true)
    private fun regenPauseWorldPackets(packet: Packet<*>, callback: CallbackInfoReturnable<Boolean>) {
        if ((server as RegenServer).regenIsRebuilding() && packet !is ServerboundKeepAlivePacket) {
            callback.returnValue = false
        }
    }
}
