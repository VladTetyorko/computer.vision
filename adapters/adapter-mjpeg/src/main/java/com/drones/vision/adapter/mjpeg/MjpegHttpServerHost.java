package com.drones.vision.adapter.mjpeg;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the single {@link HttpServer} shared by every feed one {@code MjpegFeedTransmitter}
 * <b>instance</b> transmits: lazy start on first use, per-feed context registration/removal, and
 * the only path that actually shuts the server (and its dispatch thread pool) down. Extracted
 * from {@code MjpegFeedTransmitter} — see its class javadoc/MODULE.md for the "one server per
 * instance, not per feed" rationale, which this class preserves unchanged — so that lifecycle
 * concern is testable independent of the per-viewer decode/encode/pace loop ({@link
 * MjpegViewerSession}).
 *
 * <p>Package-private: {@code MjpegFeedTransmitter} is this class's only caller.
 *
 * <p><b>Dispatch pool sizing:</b> when {@link MjpegSettings.Transmit#maxViewerThreads()} is
 * absent (the default), the pool is an unbounded {@link Executors#newCachedThreadPool()} —
 * today's behavior, preserved exactly. When present, it is a fixed-size pool of that many
 * threads; either way the pool uses the JDK's default (unprefixed) thread factory, deliberately
 * <b>not</b> the {@code "mjpeg-"} prefix — see {@link MjpegViewerSession}'s per-viewer thread
 * rename for why that prefix is reserved for genuinely active per-viewer work.
 */
final class MjpegHttpServerHost implements Closeable {

    private final String bindHost;
    private final int maxViewerThreads; // <= 0 means unbounded (today's cached-pool behavior)

    private final Object lock = new Object();
    private volatile HttpServer server;
    private volatile ExecutorService executor;

    MjpegHttpServerHost(MjpegSettings.Transmit transmitSettings) {
        this.bindHost = transmitSettings.bindHost();
        this.maxViewerThreads = transmitSettings.maxViewerThreads().orElse(0);
    }

    /** Starts the shared server on first call (idempotent) and returns its bound port. */
    int ensureStartedPort() {
        return ensureStarted().getAddress().getPort();
    }

    void createContext(String path, HttpHandler handler) {
        ensureStarted().createContext(path, handler);
    }

    /** No-op if the server was never started (mirrors the pre-extraction guard). */
    void removeContext(String path) {
        HttpServer existing = server;
        if (existing != null) {
            existing.removeContext(path);
        }
    }

    private HttpServer ensureStarted() {
        HttpServer existing = server;
        if (existing != null) {
            return existing;
        }
        synchronized (lock) {
            if (server == null) {
                try {
                    HttpServer created = HttpServer.create(new InetSocketAddress(bindHost, 0), 0);
                    ExecutorService createdExecutor = maxViewerThreads > 0
                            ? Executors.newFixedThreadPool(maxViewerThreads)
                            : Executors.newCachedThreadPool();
                    created.setExecutor(createdExecutor);
                    created.start();
                    server = created;
                    executor = createdExecutor;
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to start the MJPEG feed HTTP server", e);
                }
            }
            return server;
        }
    }

    /**
     * Shuts the server down (releasing its port) and its dispatch pool. Idempotent; safe to call
     * even if the server was never started.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }
}
