package com.drones.mavlink.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>Dials</b> out to a fixed remote {@code host:port} over TCP. The one implementation where
 * {@link #preservesMessageBoundaries()} is honestly {@code false}: a single {@link #poll} may
 * return less than one frame, more than one, or a frame split across several polls — see
 * {@link MavlinkLink}'s own javadoc for why that must never be mistaken for "one poll = one
 * message."
 *
 * <p>{@link #send} is guarded by a write lock (unlike UDP, a plain {@link java.net.Socket}'s
 * {@link OutputStream} is not safe for concurrent writers). {@code target} is accepted for
 * interface uniformity but not otherwise used: a TCP client link has exactly one possible
 * destination, its connected remote, which {@link #defaultTarget()} also reports.
 */
public final class TcpClientLink implements MavlinkLink {

    private final LinkId id;
    private final Socket socket;
    private final LinkPeer remote;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte[] buffer = new byte[8192];

    /** Connects immediately, blocking up to {@code connectTimeout}. */
    public TcpClientLink(String host, int port, Duration connectTimeout) throws IOException {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), Timeouts.clampMillis(connectTimeout));
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.remote = new LinkPeer(host, port);
        this.id = new LinkId("tcp-client:" + host + ":" + port);
    }

    @Override
    public LinkId id() {
        return id;
    }

    @Override
    public boolean preservesMessageBoundaries() {
        return false;
    }

    @Override
    public ByteChunk poll(Duration timeout) throws IOException {
        if (closed.get()) {
            return null;
        }
        try {
            socket.setSoTimeout(Timeouts.clampMillis(timeout));
            int read = in.read(buffer);
            if (read < 0) {
                close(); // the peer ended the stream in an orderly way -- treat like any other close
                return null;
            }
            return new ByteChunk(Arrays.copyOf(buffer, read), read, remote, Instant.now());
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            if (closed.get()) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public void send(byte[] frame, int off, int len, LinkPeer target) throws IOException {
        Objects.requireNonNull(target, "target");
        synchronized (writeLock) {
            out.write(frame, off, len);
            out.flush();
        }
    }

    @Override
    public LinkPeer defaultTarget() {
        return remote;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort -- the socket is going away either way
            }
        }
    }
}
