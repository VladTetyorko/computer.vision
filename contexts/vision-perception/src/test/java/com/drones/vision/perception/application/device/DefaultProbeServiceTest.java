package com.drones.vision.perception.application.device;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;
import com.drones.vision.perception.domain.port.VideoSourcePort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.drones.vision.perception.application.stream.UnsupportedProtocolException;
import com.drones.vision.perception.application.pipeline.VideoSourceRegistry;

class DefaultProbeServiceTest {

    private static final Flow.Subscription NOOP_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    };

    private static final Duration FAST_FRAME_TIMEOUT = Duration.ofMillis(150);
    private static final Duration FAST_TELEMETRY_TIMEOUT = Duration.ofMillis(150);

    private static StreamDescriptor descriptor(String protocol, String uri, Map<String, String> options) {
        return new StreamDescriptor(protocol, URI.create(uri), options);
    }

    private static VideoFrame frame(PixelFormat format) {
        return new VideoFrame(StreamId.random(), 0L, Instant.now(), 1280, 720, format,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
    }

    private static VideoSourcePort videoSourceEmitting(VideoFrame emitted) {
        VideoSourcePort port = mock(VideoSourcePort.class);
        when(port.supports(any())).thenReturn(true);
        when(port.open(any(), any())).thenAnswer(invocation ->
                (Flow.Publisher<VideoFrame>) subscriber -> {
                    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                    subscriber.onNext(emitted);
                });
        return port;
    }

    private static VideoSourcePort videoSourceFailingWith(String message) {
        VideoSourcePort port = mock(VideoSourcePort.class);
        when(port.supports(any())).thenReturn(true);
        when(port.open(any(), any())).thenAnswer(invocation ->
                (Flow.Publisher<VideoFrame>) subscriber -> {
                    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                    subscriber.onError(new RuntimeException(message));
                });
        return port;
    }

    private static VideoSourcePort videoSourceCompletingWithNoFrame() {
        VideoSourcePort port = mock(VideoSourcePort.class);
        when(port.supports(any())).thenReturn(true);
        when(port.open(any(), any())).thenAnswer(invocation ->
                (Flow.Publisher<VideoFrame>) subscriber -> {
                    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                    subscriber.onComplete();
                });
        return port;
    }

    private static VideoSourcePort videoSourceThatNeverSignals() {
        VideoSourcePort port = mock(VideoSourcePort.class);
        when(port.supports(any())).thenReturn(true);
        when(port.open(any(), any())).thenAnswer(invocation ->
                (Flow.Publisher<VideoFrame>) subscriber -> subscriber.onSubscribe(NOOP_SUBSCRIPTION));
        return port;
    }

    private static VideoSourcePort videoSourceThatThrowsOnOpen(String message) {
        VideoSourcePort port = mock(VideoSourcePort.class);
        when(port.supports(any())).thenReturn(true);
        when(port.open(any(), any())).thenThrow(new RuntimeException(message));
        return port;
    }

    private static TelemetrySourcePort telemetrySourceEmittingOneSample() {
        TelemetrySourcePort port = mock(TelemetrySourcePort.class);
        when(port.supports(any())).thenReturn(true);
        when(port.open(any())).thenAnswer(invocation ->
                (Flow.Publisher<Telemetry>) subscriber -> {
                    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                    subscriber.onNext(new Telemetry(DeviceId.random(), Instant.now(), 1.0, 2.0, null, null, 80.0,
                            Map.of()));
                });
        return port;
    }

    private DefaultProbeService service(VideoSourcePort videoSource, List<TelemetrySourcePort> telemetrySources) {
        return new DefaultProbeService(new VideoSourceRegistry(List.of(videoSource)), telemetrySources,
                FAST_FRAME_TIMEOUT, FAST_TELEMETRY_TIMEOUT);
    }

    // --- Success -------------------------------------------------------------

    @Test
    void probeReturnsTheGrabbedFrameAndDerivedMetadata() {
        VideoFrame emitted = frame(PixelFormat.JPEG);
        VideoSourcePort videoSource = videoSourceEmitting(emitted);
        ProbeResult result = service(videoSource, List.of())
                .probe(descriptor("sim", "sim://cam", Map.of("fps", "24")));

        assertEquals(emitted, result.frame());
        assertEquals("mjpeg", result.codec());
        assertEquals(24, result.fps());
        assertFalse(result.telemetryDetected());
        assertTrue(result.warnings().contains("No telemetry detected — OSD unavailable"));
    }

    @Test
    void probeClosesTheVideoSourceAfterASuccessfulGrab() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));
        service(videoSource, List.of()).probe(descriptor("sim", "sim://cam", Map.of()));

        verify(videoSource, times(1)).close(any());
    }

    @Test
    void codecIsAbsentForARawDecodedPixelFormat() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.BGR24));
        ProbeResult result = service(videoSource, List.of()).probe(descriptor("rtsp", "rtsp://cam", Map.of()));

        assertNull(result.codec());
    }

    @Test
    void fpsIsAbsentWhenTheDescriptorCarriesNoHint() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));
        ProbeResult result = service(videoSource, List.of()).probe(descriptor("sim", "sim://cam", Map.of()));

        assertNull(result.fps());
    }

    @Test
    void fpsIsAbsentWhenTheHintIsUnparseableOrNonPositive() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));
        ProbeResult bogus = service(videoSource, List.of())
                .probe(descriptor("sim", "sim://cam", Map.of("fps", "not-a-number")));
        ProbeResult zero = service(videoSource, List.of())
                .probe(descriptor("sim", "sim://cam", Map.of("fps", "0")));

        assertNull(bogus.fps());
        assertNull(zero.fps());
    }

    // --- Unrecognized protocol (400, not a probe failure) ---------------------

    @Test
    void unrecognizedProtocolThrowsUnsupportedProtocolException() {
        VideoSourcePort videoSource = mock(VideoSourcePort.class);
        when(videoSource.supports(any())).thenReturn(false);
        DefaultProbeService service = service(videoSource, List.of());

        UnsupportedProtocolException e = assertThrows(UnsupportedProtocolException.class,
                () -> service.probe(descriptor("bogus", "bogus://cam", Map.of())));
        assertEquals("bogus", e.protocol());
    }

    // --- Probe failures (422), with specific messages --------------------------

    @Test
    void translatesA401FailureIntoTheSpecificMessage() {
        VideoSourcePort videoSource = videoSourceFailingWith("Server returned 401 Unauthorized");
        DefaultProbeService service = service(videoSource, List.of());

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service.probe(descriptor("rtsp", "rtsp://192.168.1.5:554/stream", Map.of())));
        assertEquals("RTSP 401 — camera rejected the password", e.getMessage());
    }

    @Test
    void translatesAConnectionRefusedFailureNamingThePortAndProtocol() {
        VideoSourcePort videoSource = videoSourceFailingWith("Connection refused");
        DefaultProbeService service = service(videoSource, List.of());

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service.probe(descriptor("rtsp", "rtsp://192.168.1.5:554/stream", Map.of())));
        assertEquals("Connection refused on :554 — is RTSP enabled?", e.getMessage());
    }

    @Test
    void translatesAnHevcFailureIntoTheCodecMessage() {
        VideoSourcePort videoSource = videoSourceFailingWith("hevc (H265) decoder not available");
        DefaultProbeService service = service(videoSource, List.of());

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service.probe(descriptor("rtsp", "rtsp://cam", Map.of())));
        assertEquals("Codec H.265 not supported by this build", e.getMessage());
    }

    @Test
    void translatesAGenericFailureWithARawMessageFallback() {
        VideoSourcePort videoSource = videoSourceFailingWith("some unexpected native error");
        DefaultProbeService service = service(videoSource, List.of());

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service.probe(descriptor("rtsp", "rtsp://cam", Map.of())));
        assertEquals("Could not connect: some unexpected native error", e.getMessage());
    }

    @Test
    void aSynchronousFailureFromOpenItselfIsTranslatedTheSameWay() {
        VideoSourcePort videoSource = videoSourceThatThrowsOnOpen("Connection refused");
        DefaultProbeService service = service(videoSource, List.of());

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service.probe(descriptor("rtsp", "rtsp://cam:8554/x", Map.of())));
        assertEquals("Connection refused on :8554 — is RTSP enabled?", e.getMessage());
    }

    @Test
    void aStreamThatEndsWithoutEverProducingAFrameIsAProbeFailure() {
        VideoSourcePort videoSource = videoSourceCompletingWithNoFrame();
        DefaultProbeService service = service(videoSource, List.of());

        assertThrows(ProbeFailedException.class, () -> service.probe(descriptor("file", "file:///tmp/x.mp4", Map.of())));
    }

    @Test
    void aSourceThatNeverSignalsTimesOut() {
        VideoSourcePort videoSource = videoSourceThatNeverSignals();
        DefaultProbeService service = service(videoSource, List.of());

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service.probe(descriptor("rtsp", "rtsp://cam", Map.of())));
        assertTrue(e.getMessage().contains("No frame received within"));
    }

    @Test
    void theVideoSourceIsClosedEvenWhenTheProbeFails() {
        VideoSourcePort videoSource = videoSourceFailingWith("Connection refused");
        DefaultProbeService service = service(videoSource, List.of());

        assertThrows(ProbeFailedException.class, () -> service.probe(descriptor("rtsp", "rtsp://cam", Map.of())));

        verify(videoSource, times(1)).close(any());
    }

    // --- Telemetry detection ---------------------------------------------------

    @Test
    void telemetryDetectedTrueWhenARegisteredSourceProducesASampleInTime() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));
        TelemetrySourcePort telemetrySource = mock(TelemetrySourcePort.class);
        when(telemetrySource.supports(any())).thenReturn(true);
        when(telemetrySource.open(any())).thenAnswer(invocation ->
                (Flow.Publisher<Telemetry>) subscriber -> {
                    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
                    subscriber.onNext(new Telemetry(DeviceId.random(), Instant.now(), 1.0, 2.0, null, null, 80.0,
                            Map.of()));
                });

        ProbeResult result = service(videoSource, List.of(telemetrySource))
                .probe(descriptor("sim", "sim://cam", Map.of()));

        assertTrue(result.telemetryDetected());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void telemetryDetectedFalseWhenNoRegisteredSourceClaimsTheDevice() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));
        TelemetrySourcePort telemetrySource = mock(TelemetrySourcePort.class);
        when(telemetrySource.supports(any())).thenReturn(false);

        ProbeResult result = service(videoSource, List.of(telemetrySource))
                .probe(descriptor("rtsp", "rtsp://cam", Map.of()));

        assertFalse(result.telemetryDetected());
    }

    @Test
    void telemetryDetectedFalseWhenTheClaimingSourceNeverProducesASample() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));
        TelemetrySourcePort telemetrySource = mock(TelemetrySourcePort.class);
        when(telemetrySource.supports(any())).thenReturn(true);
        when(telemetrySource.open(any())).thenAnswer(invocation ->
                (Flow.Publisher<Telemetry>) subscriber -> subscriber.onSubscribe(NOOP_SUBSCRIPTION));

        ProbeResult result = service(videoSource, List.of(telemetrySource))
                .probe(descriptor("sim", "sim://cam", Map.of()));

        assertFalse(result.telemetryDetected());
        verify(telemetrySource, times(1)).close(any());
    }

    // --- Telemetry-only links (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §3) ---------

    @Test
    void aProtocolOnlyATelemetrySourceClaimsIsProvenByASampleInsteadOfAFrame() {
        VideoSourcePort videoSource = mock(VideoSourcePort.class);
        when(videoSource.supports(any())).thenReturn(false);
        TelemetrySourcePort telemetrySource = telemetrySourceEmittingOneSample();

        ProbeResult result = service(videoSource, List.of(telemetrySource))
                .probe(descriptor("mavlink", "udp://0.0.0.0:14550", Map.of()));

        assertTrue(result.telemetryOnly());
        assertNull(result.frame());
        assertTrue(result.telemetryDetected());
        assertEquals(List.of("No video on this link — telemetry only"), result.warnings());
    }

    @Test
    void aClaimedTelemetryOnlyLinkThatStaysSilentIsAProbeFailureNotABadRequest() {
        VideoSourcePort videoSource = mock(VideoSourcePort.class);
        when(videoSource.supports(any())).thenReturn(false);
        TelemetrySourcePort telemetrySource = mock(TelemetrySourcePort.class);
        when(telemetrySource.supports(any())).thenReturn(true);
        when(telemetrySource.open(any())).thenAnswer(invocation ->
                (Flow.Publisher<Telemetry>) subscriber -> subscriber.onSubscribe(NOOP_SUBSCRIPTION));

        ProbeFailedException e = assertThrows(ProbeFailedException.class,
                () -> service(videoSource, List.of(telemetrySource))
                        .probe(descriptor("mavlink", "udp://0.0.0.0:14550", Map.of())));

        assertTrue(e.getMessage().contains("udp://0.0.0.0:14550"), e.getMessage());
        verify(telemetrySource, times(1)).close(any());
    }

    @Test
    void aProtocolNeitherKindOfAdapterClaimsIsStillABadRequest() {
        VideoSourcePort videoSource = mock(VideoSourcePort.class);
        when(videoSource.supports(any())).thenReturn(false);
        TelemetrySourcePort telemetrySource = mock(TelemetrySourcePort.class);
        when(telemetrySource.supports(any())).thenReturn(false);

        UnsupportedProtocolException e = assertThrows(UnsupportedProtocolException.class,
                () -> service(videoSource, List.of(telemetrySource))
                        .probe(descriptor("bogus", "bogus://thing", Map.of())));
        assertEquals("bogus", e.protocol());
    }

    @Test
    void aVideoProbeIsUnaffectedByTheTelemetryOnlyFallback() {
        VideoSourcePort videoSource = videoSourceEmitting(frame(PixelFormat.JPEG));

        ProbeResult result = service(videoSource, List.of(telemetrySourceEmittingOneSample()))
                .probe(descriptor("rtsp", "rtsp://cam/stream", Map.of()));

        assertFalse(result.telemetryOnly());
        assertNotNull(result.frame());
        assertEquals(1280, result.frame().width());
    }

    // --- Constructor null-checks ------------------------------------------------

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultProbeService(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new DefaultProbeService(new VideoSourceRegistry(List.of()), null));
    }

    @Test
    void probeRejectsNullDescriptor() {
        DefaultProbeService service = new DefaultProbeService(new VideoSourceRegistry(List.of()), List.of());
        assertThrows(NullPointerException.class, () -> service.probe(null));
    }
}
