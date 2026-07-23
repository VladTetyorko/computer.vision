package com.drones.vision.adapter.mavlink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Bridges an {@link OutputStream} — what {@link io.dronefleet.mavlink.MavlinkConnection#send2}
 * writes one serialized packet's raw bytes to — to a fixed UDP destination.
 *
 * <p>Bytes are buffered across {@link #write} calls and only actually sent as one {@link
 * DatagramPacket} on {@link #flush()}. {@code MavlinkConnection}'s own {@code send(...)} always
 * calls {@code out.write(rawBytes); out.flush();} exactly once per outgoing message, so this
 * produces exactly one UDP datagram per MAVLink message — matching how a real telemetry radio or
 * SITL instance pushes MAVLink over UDP.
 *
 * <p>Not thread-safe (mirrors {@link MavlinkUdpInputStream}). {@link #close()} deliberately does
 * <b>not</b> close the underlying {@link DatagramSocket} — the socket is owned and closed by the
 * caller, the same "wrapper doesn't own the shared resource" convention {@code adapter-mjpeg}'s
 * {@code MjpegFeedTransmitter} uses for its shared {@code HttpServer}.
 */
final class MavlinkUdpOutputStream extends OutputStream {

    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    MavlinkUdpOutputStream(DatagramSocket socket, InetAddress address, int port) {
        this.socket = socket;
        this.address = address;
        this.port = port;
    }

    @Override
    public void write(int b) {
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        buffer.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        if (buffer.size() == 0) {
            return;
        }
        byte[] data = buffer.toByteArray();
        buffer.reset();
        socket.send(new DatagramPacket(data, data.length, address, port));
    }

    @Override
    public void close() {
        // Deliberately a no-op -- see class javadoc: the socket outlives any single wrapper.
    }
}
