package dev.screch.regen

import io.netty.channel.ChannelFutureListener
import net.minecraft.network.Connection
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

class RecordingConnection(private val memory: Boolean) : Connection(PacketFlow.SERVERBOUND) {
    val packets = mutableListOf<Packet<*>>()
    var disconnected = false

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) { packets.add(packet) }
    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) { packets.add(packet) }
    override fun send(packet: Packet<*>) { packets.add(packet) }
    override fun flushChannel() {}
    override fun isConnected(): Boolean = !disconnected
    override fun isMemoryConnection(): Boolean = memory
    override fun disconnect(details: DisconnectionDetails) { disconnected = true }
}
