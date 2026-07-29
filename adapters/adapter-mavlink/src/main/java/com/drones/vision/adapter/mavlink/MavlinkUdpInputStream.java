package com.drones.vision.adapter.mavlink;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Bridges a bound {@link DatagramSocket} to a continuous {@link InputStream} so {@link
 * io.dronefleet.mavlink.MavlinkConnection} — which expects a byte stream, not discrete datagrams
 * — can read MAVLink frames off UDP.
 *
 * <p>Each {@link #read()} call blocks on {@link DatagramSocket#receive} only once the current
 * datagram's bytes are exhausted, then serves bytes from it one at a time. This deliberately
 * concatenates successive datagrams into one logical byte stream: a MAVLink 2 frame that happens
 * to be malformed (random noise, a truncated capture, non-MAVLink traffic hitting the port) is
 * <b>not</b> specially handled here — {@code MavlinkConnection#next()} itself already scans for
 * the next valid frame-start marker and silently drops anything that doesn't parse or fails CRC
 * (see its javadoc), so garbage bytes are absorbed transparently and never surface as an
 * exception to this stream's caller. {@link #read()} never returns {@code -1}: there is no
 * natural "end of stream" for a live listening socket. A {@link DatagramSocket#close()} called
 * from another thread is what actually unblocks a pending {@link #read()}, by making the
 * in-flight {@code receive()} throw {@link java.net.SocketException} — the same
 * close-to-unblock idiom {@code adapter-mjpeg}'s {@code MjpegVideoSource} uses for its blocked
 * HTTP body read.
 *
 * <h2>Last source address (docs/DRONE-INFRA-PLAN.md I-e Stage 1)</h2>
 * {@link #lastSourceAddress()} exposes the sender address of the most recently received datagram.
 * Every TX side in this module ({@code MavlinkFeedTransmitter}, a real telemetry radio/SITL
 * instance) writes exactly one MAVLink message per datagram (see {@code MavlinkUdpOutputStream}'s
 * own javadoc), so — for the common case, ignoring the deliberately-unhandled malformed-frame
 * resync above — the datagram whose bytes fed a given decoded message is knowable to the caller
 * simply by reading this accessor immediately after the message that consumed it was decoded, on
 * this same thread. {@link MavlinkSocketHub} uses this to remember where a claimed vehicle was
 * last heard from, so a command can be sent back to it.
 *
 * <p>Not thread-safe: only ever driven by the one background read thread that owns the socket,
 * matching every other per-open runtime's read-thread convention in this codebase.
 */
final class MavlinkUdpInputStream extends InputStream {

    /** Max UDP payload over IPv4 with room to spare (actual MAVLink 2 frames are far smaller, &lt;300 bytes). */
    private static final int MAX_DATAGRAM_BYTES = 65_507;

    private final DatagramSocket socket;
    private final byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
    private int length;
    private int position;
    private InetSocketAddress lastSourceAddress;

    MavlinkUdpInputStream(DatagramSocket socket) {
        this.socket = socket;
    }

    @Override
    public int read() throws IOException {
        if (position >= length) {
            fill();
        }
        return buffer[position++] & 0xFF;
    }

    /**
     * The source address of the most recently received datagram, or {@code null} before the
     * first one has arrived. See class javadoc for the one-datagram-per-message correspondence
     * this relies on.
     */
    InetSocketAddress lastSourceAddress() {
        return lastSourceAddress;
    }

    /** Blocks until a non-empty datagram arrives; a zero-length datagram (a bare UDP "ping") is skipped. */
    private void fill() throws IOException {
        int received;
        InetSocketAddress source;
        do {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            received = packet.getLength();
            source = new InetSocketAddress(packet.getAddress(), packet.getPort());
        } while (received <= 0);
        length = received;
        position = 0;
        lastSourceAddress = source;
    }
}
