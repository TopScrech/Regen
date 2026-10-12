package dev.screch.regen;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.ArrayList;
import java.util.List;

public final class RecordingConnection extends Connection {
    public final List<Packet<?>> packets = new ArrayList<>();
    private final boolean memory;
    public boolean disconnected;

    public RecordingConnection(boolean memory) {
        super(PacketFlow.SERVERBOUND);
        this.memory = memory;
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        packets.add(packet);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        packets.add(packet);
    }

    @Override
    public void send(Packet<?> packet) {
        packets.add(packet);
    }

    @Override
    public void flushChannel() {}

    @Override
    public boolean isConnected() {
        return !disconnected;
    }

    @Override
    public boolean isMemoryConnection() {
        return memory;
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        disconnected = true;
    }
}
