package com.drones.vision.application;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DiscoveredDevice;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.DeviceDiscoveryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDiscoveryServiceTest {

    private static DiscoveredDevice device(String method, String host) {
        return new DiscoveredDevice(method, "cam-" + host, URI.create("rtsp://" + host + "/"),
                new CategoryId("ip-camera"), null, Map.of());
    }

    @Test
    void aggregatesResultsFromAllPortsRunInParallel() throws InterruptedException {
        // Each fake port counts down a shared latch (size 2) then waits on it
        // before returning. If the service ran them sequentially rather than
        // in parallel, the first port would block forever waiting for the
        // second to also count down -- which never happens until it is
        // itself started. This proves concurrency without any sleep.
        CountDownLatch bothStarted = new CountDownLatch(2);
        FakePort onvif = FakePort.rendezvousing("onvif", bothStarted, List.of(device("onvif", "10.0.0.1")));
        FakePort mdns = FakePort.rendezvousing("mdns", bothStarted, List.of(device("mdns", "10.0.0.2")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(onvif, mdns));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(2), Set.of()));

        assertTrue(result.failedMethods().isEmpty(), "no port should be reported failed: " + result.failedMethods());
        assertEquals(2, result.devices().size());
        assertTrue(result.devices().contains(device("onvif", "10.0.0.1")));
        assertTrue(result.devices().contains(device("mdns", "10.0.0.2")));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS) // safety net: a regression here must not hang the build forever
    void hangingPortIsBoundedAndReportedAsFailedWithoutStallingTheScan() {
        FakePort hanging = FakePort.hangingForever("v4l2");
        FakePort healthy = FakePort.returning("mdns", List.of(device("mdns", "10.0.0.2")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(hanging, healthy));

        Duration requestedTimeout = Duration.ofMillis(100);
        long start = System.nanoTime();
        ScanResult result =
                service.scan(new ScanRequest(requestedTimeout, Set.of()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Set.of("v4l2"), result.failedMethods());
        assertEquals(List.of(device("mdns", "10.0.0.2")), result.devices());
        // Bound is requestedTimeout + DefaultDiscoveryService.GRACE_PERIOD; allow a
        // generous scheduling margin on top so this never flakes under load,
        // while still being far, far below the port's actual (infinite) hang.
        assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0,
                "scan should return well within its bound, took " + elapsed);
    }

    @Test
    void isolatesAThrowingPortAndStillReturnsHealthyResults() {
        FakePort broken = FakePort.throwing("onvif", new RuntimeException("socket bind failed"));
        FakePort healthy = FakePort.returning("mdns", List.of(device("mdns", "10.0.0.2")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(broken, healthy));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(Set.of("onvif"), result.failedMethods());
        assertEquals(List.of(device("mdns", "10.0.0.2")), result.devices());
    }

    @Test
    void dedupsExactMethodAndAddressMatchesFromTheSamePort() {
        DiscoveredDevice duplicate = device("mdns", "10.0.0.2");
        FakePort mdns = FakePort.returning("mdns",
                List.of(duplicate, duplicate, device("mdns", "10.0.0.3")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(mdns));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(2, result.devices().size());
        assertTrue(result.devices().contains(duplicate));
        assertTrue(result.devices().contains(device("mdns", "10.0.0.3")));
    }

    @Test
    void methodFilteringOnlyRunsRequestedPorts() {
        FakePort onvif = FakePort.returning("onvif", List.of(device("onvif", "10.0.0.1")));
        FakePort mdns = FakePort.returning("mdns", List.of(device("mdns", "10.0.0.2")));
        FakePort v4l2 = FakePort.returning("v4l2", List.of(device("v4l2", "10.0.0.3")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(onvif, mdns, v4l2));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of("mdns")));

        assertEquals(List.of(device("mdns", "10.0.0.2")), result.devices());
        assertEquals(0, onvif.invocationCount());
        assertEquals(0, v4l2.invocationCount());
        assertEquals(1, mdns.invocationCount());
    }

    @Test
    void unknownRequestedMethodIsRejectedWithoutRunningAnyPort() {
        FakePort mdns = FakePort.returning("mdns", List.of(device("mdns", "10.0.0.2")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(mdns));

        assertThrows(IllegalArgumentException.class,
                () -> service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of("bluetooth"))));
        assertEquals(0, mdns.invocationCount(), "no port should run when the request itself is rejected");
    }

    @Test
    void emptyMethodsMeansAllRegisteredPortsRun() {
        FakePort onvif = FakePort.returning("onvif", List.of(device("onvif", "10.0.0.1")));
        FakePort mdns = FakePort.returning("mdns", List.of(device("mdns", "10.0.0.2")));
        DiscoveryService service = new DefaultDiscoveryService(List.of(onvif, mdns));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(1, onvif.invocationCount());
        assertEquals(1, mdns.invocationCount());
        assertEquals(2, result.devices().size());
    }

    @Test
    void mergesCrossMethodCandidatesSharingAHostIntoOneDevice() {
        StreamDescriptor rtspStream = new StreamDescriptor("rtsp", URI.create("rtsp://10.0.0.5/stream1"), Map.of());
        DiscoveredDevice onvifCandidate = new DiscoveredDevice("onvif", "Front Door Camera",
                URI.create("http://10.0.0.5:80/onvif/device_service"), null, null,
                Map.of("scopes", "onvif://www.onvif.org/name/FrontDoor"));
        DiscoveredDevice mdnsCandidate = new DiscoveredDevice("mdns", "10.0.0.5",
                URI.create("rtsp://10.0.0.5/stream1"), new CategoryId("ip-camera"), rtspStream,
                Map.of("port", "554"));
        FakePort onvif = FakePort.returning("onvif", List.of(onvifCandidate));
        FakePort mdns = FakePort.returning("mdns", List.of(mdnsCandidate));
        DiscoveryService service = new DefaultDiscoveryService(List.of(onvif, mdns));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(1, result.devices().size(),
                "same-host onvif+mdns candidates must merge into one: " + result.devices());
        DiscoveredDevice merged = result.devices().get(0);
        assertEquals("mdns+onvif", merged.method(), "method join must be alphabetical, not discovery order");
        assertEquals("Front Door Camera", merged.name(),
                "onvif's user-assigned name must win over mdns' literal-host name");
        assertEquals(rtspStream, merged.suggestedStream());
        assertEquals(URI.create("rtsp://10.0.0.5/stream1"), merged.address(),
                "merged address must come from the candidate that supplied the stream");
        assertEquals(new CategoryId("ip-camera"), merged.suggestedCategory());
        assertEquals(Map.of("scopes", "onvif://www.onvif.org/name/FrontDoor", "port", "554"), merged.details(),
                "non-colliding detail keys stay bare (no method prefix needed)");
    }

    @Test
    void nullHostCandidatesGroupByExactAddressAndDoNotMergeAcrossDevices() {
        // v4l2's file: URIs have no host -- two distinct /dev/videoN devices
        // must stay two separate DiscoveredDevices, never merged.
        DiscoveredDevice video0 = new DiscoveredDevice("v4l2", "USB Camera 0",
                URI.create("file:///dev/video0"), null, null, Map.of());
        DiscoveredDevice video1 = new DiscoveredDevice("v4l2", "USB Camera 1",
                URI.create("file:///dev/video1"), null, null, Map.of());
        FakePort v4l2 = FakePort.returning("v4l2", List.of(video0, video1));
        DiscoveryService service = new DefaultDiscoveryService(List.of(v4l2));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(2, result.devices().size());
        assertTrue(result.devices().contains(video0));
        assertTrue(result.devices().contains(video1));
    }

    @Test
    void sameMethodCandidatesSharingAHostAreNeverCrossMethodMerged() {
        // Two distinct mdns services on the same host, same method: sharing a
        // host alone must not trigger a merge -- only 2+ distinct methods do.
        DiscoveredDevice serviceA = device("mdns", "10.0.0.9");
        DiscoveredDevice serviceB = new DiscoveredDevice("mdns", "cam-10.0.0.9-onvif-service",
                URI.create("http://10.0.0.9:8080/onvif"), null, null, Map.of());
        FakePort mdns = FakePort.returning("mdns", List.of(serviceA, serviceB));
        DiscoveryService service = new DefaultDiscoveryService(List.of(mdns));

        ScanResult result =
                service.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(2, result.devices().size(),
                "same-method candidates sharing a host must not be cross-method merged");
        assertTrue(result.devices().contains(serviceA));
        assertTrue(result.devices().contains(serviceB));
    }

    @Test
    void methodJoinOrderIsAlphabeticalRegardlessOfDiscoveryOrder() {
        DiscoveredDevice onvifCandidate = new DiscoveredDevice("onvif", "onvif-cam",
                URI.create("http://10.0.0.7/onvif"), null, null, Map.of());
        DiscoveredDevice mdnsCandidate = new DiscoveredDevice("mdns", "mdns-cam",
                URI.create("rtsp://10.0.0.7/stream"), null, null, Map.of());
        FakePort onvifPort = FakePort.returning("onvif", List.of(onvifCandidate));
        FakePort mdnsPort = FakePort.returning("mdns", List.of(mdnsCandidate));

        // Register the ports in both orders -- the merged method string must
        // be identical either way, since it is sorted, not encounter-order.
        DiscoveryService onvifRegisteredFirst = new DefaultDiscoveryService(List.of(onvifPort, mdnsPort));
        DiscoveryService mdnsRegisteredFirst = new DefaultDiscoveryService(List.of(mdnsPort, onvifPort));

        ScanResult r1 =
                onvifRegisteredFirst.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));
        ScanResult r2 =
                mdnsRegisteredFirst.scan(new ScanRequest(Duration.ofSeconds(1), Set.of()));

        assertEquals(1, r1.devices().size());
        assertEquals(1, r2.devices().size());
        assertEquals("mdns+onvif", r1.devices().get(0).method());
        assertEquals("mdns+onvif", r2.devices().get(0).method());
    }

    /**
     * Configurable {@link DeviceDiscoveryPort} test double: records
     * invocation counts and runs a scripted behavior instead of doing real
     * I/O, so tests control concurrency/timeout/failure scenarios
     * deterministically.
     */
    private static final class FakePort implements DeviceDiscoveryPort {
        private final String method;
        private final Function<Duration, List<DiscoveredDevice>> behavior;
        private final AtomicInteger invocations = new AtomicInteger();

        private FakePort(String method, Function<Duration, List<DiscoveredDevice>> behavior) {
            this.method = method;
            this.behavior = behavior;
        }

        static FakePort returning(String method, List<DiscoveredDevice> results) {
            return new FakePort(method, timeout -> results);
        }

        static FakePort throwing(String method, RuntimeException exception) {
            return new FakePort(method, timeout -> {
                throw exception;
            });
        }

        /** Blocks forever (until the JVM/test process ends) -- a deliberately hanging adapter. */
        static FakePort hangingForever(String method) {
            CountDownLatch neverCounted = new CountDownLatch(1);
            return new FakePort(method, timeout -> {
                try {
                    neverCounted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            });
        }

        /** Counts down {@code startedLatch} then waits for it to reach zero, proving concurrent execution. */
        static FakePort rendezvousing(String method, CountDownLatch startedLatch, List<DiscoveredDevice> results) {
            return new FakePort(method, timeout -> {
                startedLatch.countDown();
                try {
                    if (!startedLatch.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "peer port(s) never started -- ports are not running in parallel");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return results;
            });
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public List<DiscoveredDevice> scan(Duration timeout) {
            invocations.incrementAndGet();
            return behavior.apply(timeout);
        }

        int invocationCount() {
            return invocations.get();
        }
    }
}
