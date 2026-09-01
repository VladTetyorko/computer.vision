package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkFeedTransmitter;
import com.drones.vision.adapter.mavlink.MavlinkSettings;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionOnboardingProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import com.drones.vision.warehouse.domain.model.Device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAVLINK-COMMANDS P3: pins the wiring the 2026-08-26 architecture audit flagged as unverified —
 * "does every production {@code MavlinkSession} actually register an {@code onLinkFailure}
 * listener, or does the hook merely exist" (docs/plans/active/mavlink-commands/R3-codebase-inventory.md
 * §4d/§6).
 *
 * <h2>Verification finding (no code change needed)</h2>
 * Every production {@code MavlinkSession} is constructed in exactly one place: {@code
 * MavlinkGateway}'s package-private {@code (MavlinkLink, MavlinkSettings)} constructor
 * (drone-link/mavlink's {@code MavlinkGateway.java:168-176}), which the production {@code (String,
 * int, MavlinkSettings)} constructor unconditionally delegates to
 * ({@code MavlinkGateway.java:156-158}). That constructor registers {@code
 * session.onLinkFailure((linkId, cause) -> handleLinkFailure(cause))} at line 176 — before any
 * device registration, claim policy, or subscription exists — so a failure occurring the instant
 * after bind still reaches every registration the gateway ever accumulates. There is no other
 * production call site of {@code new MavlinkSession(...)} anywhere in this repository (confirmed by
 * {@code grep -rn "new MavlinkSession("} — every other hit is a mavlink-core unit test).
 * {@code MavlinkGateway} itself has exactly two production callers: {@code
 * MavlinkTelemetrySource.java:322} (the RX hub every device shares) and {@code
 * MavlinkVehicleConfigurator.java:494} (the onboarding probe's self-bound gateway) — both go through
 * the same constructor, so both get the listener for free. {@code MavlinkHeartbeatScanner}'s
 * self-bind discovery path is the one MAVLink codepath that does <em>not</em> build a {@code
 * MavlinkSession} at all — it opens a bare {@code UdpListenLink}+{@code FrameReader} for one bounded
 * scan and releases it, with its own synchronous bind-failure handling (one WARN log, empty result);
 * it was never a candidate for a persistent-session "silent death" failure class in the first place.
 *
 * <p>{@code handleLinkFailure} ({@code MavlinkGateway.java:347-351}) logs a {@code WARNING} naming
 * the failed link ({@code link.id()}) and closes every registered device's {@code
 * SubmissionPublisher} exceptionally — the exact publisher {@code UsageTracker#subscribeTelemetry}
 * (vision-perception) wraps in a {@code SupervisedPublisher}, whose {@code onError} handling is what
 * already produces the cockpit's stale-not-live telemetry rendering (OPERATOR-UX-3): a failed link
 * stops producing samples, so the device's telemetry ages past whatever staleness threshold the UI
 * already reads — no new, parallel status concept invented or needed.
 *
 * <p>vision-app itself has no seam to add a listener to: {@code MavlinkGateway}/{@code
 * MavlinkSession} construction is entirely encapsulated inside adapter-mavlink's package-private
 * {@code MavlinkGateway} — vision-app only ever sees the public {@link MavlinkTelemetrySource}
 * facade. There is nothing to wire here.
 *
 * <h2>What this test pins instead</h2>
 * Since the failure-handling mechanism itself is already covered where it actually lives
 * ({@code MavlinkGatewayLinkFailureTest} in drone-link/mavlink, {@code
 * MavlinkSessionLinkFailureTest} in drone-link/mavlink-core — both exercise a genuine {@code
 * IOException} via a hand-built failing {@code MavlinkLink}, a seam only reachable from inside
 * adapter-mavlink's own package), the regression actually owned by <em>this</em> module's wiring is
 * different: that {@code SystemStatusWiring#mavlinkLinkStatus} reads its health off the exact same
 * {@code MavlinkTelemetrySource} instance the rest of the app's MAVLink wiring shares — not a stale
 * or disconnected copy — so that when a link failure closes a gateway's publishers (proven
 * adapter-side), {@code GET /api/system/status} is guaranteed to observe it. This test builds the
 * real production object graph ({@code TelemetryWiring#toMavlinkSettings} → a real, socket-bound
 * {@link MavlinkTelemetrySource} → {@code SystemStatusWiring#mavlinkLinkStatus}), sends a real
 * MAVLink heartbeat over real loopback UDP via {@link MavlinkFeedTransmitter}, and proves the status
 * port's answer is live, not static: {@link Health#UNKNOWN} before anything is heard, something else
 * once a real vehicle has been claimed.
 */
class MavlinkLinkStatusWiringTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mavlinkLinkStatusObservesTheSameTelemetrySourceRealTrafficClaims() throws Exception {
        VisionMavlinkProperties mavlinkProperties = new VisionMavlinkProperties("127.0.0.1", Duration.ofSeconds(30),
                32, Duration.ofSeconds(5), Duration.ofSeconds(2), 5.0, 20.0, Duration.ofSeconds(2), null, null);
        VisionRcProperties rcProperties = new VisionRcProperties(300L, 33, 10, 50, 3);
        VisionOnboardingProperties onboardingProperties = new VisionOnboardingProperties(
                new VisionOnboardingProperties.Probe(false, Duration.ofSeconds(10), Duration.ofSeconds(7), java.util.List.of()),
                new VisionOnboardingProperties.Remediate(new VisionOnboardingProperties.Remediate.MessageInterval(false)),
                new VisionOnboardingProperties.Passport(false));

        MavlinkSettings settings = TelemetryWiring.toMavlinkSettings(mavlinkProperties, rcProperties, onboardingProperties);
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settings);
        SubsystemStatusPort statusPort = new SystemStatusWiring().mavlinkLinkStatus(source, mavlinkProperties);

        SubsystemStatus beforeAnyTraffic = statusPort.status();
        assertEquals("mavlink-link", beforeAnyTraffic.id());
        assertEquals(Health.UNKNOWN, beforeAnyTraffic.health(),
                "no vehicle claimed yet -- must read UNKNOWN, not a stale/default OK");

        int port = freePort();
        Device device = new Device(DeviceId.random(), "mavlink-link-status-wiring-test-device",
                Set.of(Capability.TELEMETRY), new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port), Map.of()));
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedId feedId = FeedId.random();

        try {
            Flow.Publisher<Telemetry> publisher = source.open(device);
            publisher.subscribe(new NoopSubscriber());

            transmitter.start(feedId, new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                    Map.of("route", "10.00000,30.00000;10.00050,30.00000", "sysid", "61",
                            "positionRateHz", "20", "speedMps", "5")));

            SubsystemStatus afterRealTraffic = awaitStatusNotUnknown(statusPort, Duration.ofSeconds(20));

            assertNotEquals(Health.UNKNOWN, afterRealTraffic.health(),
                    "a real heartbeat over the real socket must flip the SAME status port SystemStatusWiring "
                            + "builds from mavlinkTelemetrySource -- proves the status pipe is live, not a "
                            + "disconnected/parallel reading");
            assertTrue(source.claimedVehicleHealth().containsKey(device.id()),
                    "the telemetry source's own claim map -- the exact Supplier mavlinkLinkStatus reads -- "
                            + "must show the same claimed device the status port just reported on");
        } finally {
            transmitter.stop(feedId);
            source.close(device.id());
        }
    }

    private static SubsystemStatus awaitStatusNotUnknown(SubsystemStatusPort port, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        SubsystemStatus last;
        do {
            last = port.status();
            if (last.health() != Health.UNKNOWN) {
                return last;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return last;
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class NoopSubscriber implements Flow.Subscriber<Telemetry> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Telemetry item) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }
}
