package com.drones.mavlink.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real loopback TCP against a plain {@link ServerSocket} -- no mocks. */
class TcpClientLinkTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void dialsOutAndExchangesBytesWithAPlainServerSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Socket> accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (TcpClientLink client = new TcpClientLink("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2))) {
                assertFalse(client.preservesMessageBoundaries());
                assertEquals("127.0.0.1", client.defaultTarget().host());
                assertEquals(server.getLocalPort(), client.defaultTarget().port());

                Socket serverSide = accepted.get(2, java.util.concurrent.TimeUnit.SECONDS);
                try (serverSide) {
                    byte[] payload = "hello-tcp".getBytes(StandardCharsets.UTF_8);
                    client.send(payload, 0, payload.length, client.defaultTarget());

                    InputStream in = serverSide.getInputStream();
                    byte[] received = in.readNBytes(payload.length);
                    assertArrayEquals(payload, received);

                    OutputStream out = serverSide.getOutputStream();
                    byte[] reply = "hello-back".getBytes(StandardCharsets.UTF_8);
                    out.write(reply);
                    out.flush();

                    ByteChunk chunk = client.poll(TIMEOUT);
                    assertTrue(chunk.length() > 0);
                    assertArrayEquals(reply, java.util.Arrays.copyOf(chunk.data(), chunk.length()));
                }
            }
        }
    }

    @Test
    void pollReturnsNullOnTimeoutWhenNothingArrives() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Socket> accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try (TcpClientLink client = new TcpClientLink("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2))) {
                try (Socket serverSide = accepted.get(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    assertNull(client.poll(Duration.ofMillis(50)));
                }
            }
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Socket> accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            TcpClientLink client = new TcpClientLink("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));
            accepted.get(2, java.util.concurrent.TimeUnit.SECONDS);
            client.close();
            client.close(); // must not throw
            assertNull(client.poll(Duration.ofMillis(50)));
        }
    }
}
