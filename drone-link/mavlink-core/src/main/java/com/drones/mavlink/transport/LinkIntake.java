package com.drones.mavlink.transport;

import java.time.Instant;

/**
 * A snapshot of what has arrived at a link's socket, counted <b>pre-parse</b> — before any MAVLink
 * resync or decoding is attempted (that is {@code com.drones.mavlink.codec}'s job, several layers
 * up). This is deliberately the rawest signal this module can produce: it answers "did anything
 * reach this socket at all", which a silently-dead UDP port cannot answer any other way — {@code
 * DatagramSocket#receive} on a port nothing is transmitting to blocks forever with no error, no
 * timeout distinct from "nothing yet," and no log (SOURCE-ONBOARDING-2 §2.4 S1).
 *
 * <p>Read {@code datagramsReceived} against {@code framesDecoded} (a higher-level count, kept by
 * whoever decodes frames off this link) to tell two very different failures apart: zero datagrams
 * means nothing is reaching the socket at all (wrong network, wrong port, firewalled); datagrams
 * arriving but nothing decoding means something is reaching the port that is not a valid MAVLink 2
 * frame (wrong protocol, MAVLink 1, garbage). Neither count on its own can distinguish those.
 *
 * @param datagramsReceived total datagrams received since the link was opened; monotonic, never reset
 * @param bytesReceived     total payload bytes received since the link was opened; monotonic, never reset
 * @param lastDatagramAt    when the most recent datagram arrived, or {@code null} if none ever has
 */
public record LinkIntake(long datagramsReceived, long bytesReceived, Instant lastDatagramAt) {

    public LinkIntake {
        if (datagramsReceived < 0) {
            throw new IllegalArgumentException("datagramsReceived must be >= 0, got " + datagramsReceived);
        }
        if (bytesReceived < 0) {
            throw new IllegalArgumentException("bytesReceived must be >= 0, got " + bytesReceived);
        }
    }
}
