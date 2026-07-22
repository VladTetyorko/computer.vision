package com.drones.vision.adapter.discovery.mdns;

import com.drones.vision.domain.model.DiscoveredDevice;
import org.junit.jupiter.api.Test;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real jmdns integration test: registers an {@code _rtsp._tcp} service on a
 * loopback-bound {@link JmDNS} instance and asserts {@link MdnsScanner}
 * (also bound to loopback, via its test constructor seam) finds it.
 *
 * <p>This exercises real mDNS traffic -- multicast announce/query/response
 * -- rather than a canned fixture, so it depends on the sandbox correctly
 * routing multicast over the loopback interface. That held up in manual
 * runs in this environment; if it starts flaking or hanging in CI, disable
 * it (add {@code @Disabled} with the reason) and rely on {@link
 * MdnsScannerTest}'s pure mapping-logic tests instead, per the discovery
 * plan's documented fallback for this case.
 */
class MdnsScannerLoopbackTest {

    @Test
    void findsARealRegisteredRtspServiceOverLoopbackMdns() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ServiceInfo serviceInfo = ServiceInfo.create("_rtsp._tcp.local.", "loopback-test-cam", 8554, "path=/stream");

        try (JmDNS registrar = JmDNS.create(loopback)) {
            registrar.registerService(serviceInfo);

            MdnsScanner scanner = new MdnsScanner(loopback);
            List<DiscoveredDevice> found = scanner.scan(Duration.ofSeconds(6));

            Optional<DiscoveredDevice> match = found.stream()
                    .filter(d -> "mdns".equals(d.method()))
                    .filter(d -> d.name() != null && d.name().contains("loopback-test-cam"))
                    .findFirst();

            assertTrue(match.isPresent(),
                    "expected to find the loopback-registered _rtsp service; got: " + found);
        } finally {
            // Best-effort: jmdns close() already unregisters, this just avoids
            // lingering goodbye-packet state across quick successive test runs.
        }
    }

    /**
     * Regression test for the "mdns scan takes longer than its timeout"
     * contract bug: {@link com.drones.vision.domain.port.out.DeviceDiscoveryPort#scan(Duration)}
     * must return within ~timeout because {@code DiscoveryService} (the
     * caller, in {@code vision-application}) hard-bounds its wait at {@code
     * timeout + 200ms} grace and reports anything slower as a failed method.
     *
     * <p>Before the fix, {@link MdnsScanner} spent the <em>full</em> timeout
     * inside each concurrent {@code JmDNS.list(type, timeoutMillis)} call and
     * then added its own 250ms join grace <em>on top</em> of that (plus
     * {@code JmDNS.create()} setup time before either), so a healthy scan
     * against an empty/quiet network routinely finished at {@code
     * timeout + setup + 250ms} -- past the caller's grace window.
     *
     * <p>The assertion is deliberately generous (checked against the same
     * {@code timeout + 200ms} boundary the caller enforces, not a
     * razor-thin margin) so this stays robust on slow/loaded CI machines
     * while still catching a scanner that structurally overruns its budget.
     */
    @Test
    void scanReturnsWithinTimeoutPlusCallerGrace() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Duration timeout = Duration.ofMillis(1500);
        Duration callerGrace = Duration.ofMillis(200); // mirrors DiscoveryService.GRACE_PERIOD

        MdnsScanner scanner = new MdnsScanner(loopback);

        long startNanos = System.nanoTime();
        List<DiscoveredDevice> found = scanner.scan(timeout);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis < timeout.plus(callerGrace).toMillis(),
                "mdns scan took " + elapsedMillis + "ms for a " + timeout.toMillis()
                        + "ms timeout, which exceeds the caller's " + callerGrace.toMillis()
                        + "ms grace window; found=" + found);
    }
}
