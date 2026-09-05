package com.drones.vision.adapter.mavlink;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A snapshot of one bind address's telemetry intake — the answer to
 * {@code docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md}'s P1 ("nothing arrives") and P2 ("bytes
 * arrive, nothing decodes"), and the shape {@code GET /api/discovery/status}'s
 * {@code telemetryIntake} (C2) is built from field-for-field.
 *
 * <p>{@code datagramsReceived}/{@code bytesReceived}/{@code lastDatagramAt} are counted pre-parse,
 * at the socket ({@code mavlink-core}'s {@code LinkIntake} — see that record's own javadoc);
 * {@code framesDecoded} is counted one layer up, after {@code mavlink-core} has successfully
 * resynced and decoded a frame, regardless of whether any registration claims it. Comparing the
 * two is the actual diagnostic: zero datagrams means nothing is reaching the socket at all (wrong
 * network, wrong port, firewalled); datagrams arriving with zero frames decoded means something is
 * reaching the port that is not a valid MAVLink 2 frame (wrong protocol, MAVLink 1, garbage).
 * Neither count alone can tell those apart.
 *
 * @param bound             whether a gateway is currently bound to this address at all — {@code
 *                          false} only when nothing has ever opened or held it, or it has since
 *                          closed; every other field is zeroed/empty in that case ({@link #unbound})
 * @param bindAddress       {@code "host:port"} this status describes
 * @param lobbyHeld         whether the claim-free standing lobby ({@code
 *                          MavlinkTelemetrySource#holdLobby(int)}) currently holds this address open
 * @param datagramsReceived total UDP datagrams received on this address's socket, pre-parse,
 *                          monotonic, never reset; {@code 0} when {@code bound} is {@code false}
 * @param bytesReceived     total payload bytes received on this address's socket, pre-parse,
 *                          monotonic, never reset; {@code 0} when {@code bound} is {@code false}
 * @param lastDatagramAt    when the most recent datagram arrived, or {@code null} if none ever has
 * @param framesDecoded     total MAVLink frames successfully decoded and dispatched off this
 *                          address's socket, claimed or not; monotonic, never reset
 * @param unclaimedSysids   system ids currently heard but claimed by no registered device
 * @param claimedSysids     system ids currently claimed by a registered device
 */
public record MavlinkIntakeStatus(
        boolean bound,
        String bindAddress,
        boolean lobbyHeld,
        long datagramsReceived,
        long bytesReceived,
        Instant lastDatagramAt,
        long framesDecoded,
        List<Integer> unclaimedSysids,
        List<Integer> claimedSysids) {

    public MavlinkIntakeStatus {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress must not be blank");
        }
        if (datagramsReceived < 0) {
            throw new IllegalArgumentException("datagramsReceived must be >= 0, got " + datagramsReceived);
        }
        if (bytesReceived < 0) {
            throw new IllegalArgumentException("bytesReceived must be >= 0, got " + bytesReceived);
        }
        if (framesDecoded < 0) {
            throw new IllegalArgumentException("framesDecoded must be >= 0, got " + framesDecoded);
        }
        unclaimedSysids = List.copyOf(Objects.requireNonNull(unclaimedSysids, "unclaimedSysids must not be null"));
        claimedSysids = List.copyOf(Objects.requireNonNull(claimedSysids, "claimedSysids must not be null"));
    }

    /**
     * The honest answer for a bind address nothing has ever opened or held, or that has since
     * closed — this is P1's "nothing arrives" case at its most literal: not merely zero counters,
     * but no socket bound to observe anything with.
     */
    public static MavlinkIntakeStatus unbound(String bindAddress) {
        return new MavlinkIntakeStatus(false, bindAddress, false, 0, 0, null, 0, List.of(), List.of());
    }
}
