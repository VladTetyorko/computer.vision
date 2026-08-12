package com.drones.vision.perception.application.device;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.flight.domain.port.TelemetrySourcePort;
import com.drones.vision.perception.domain.port.VideoSourcePort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.drones.vision.perception.application.pipeline.VideoSourceRegistry;

/**
 * {@link ProbeService} backed by the same {@link VideoSourcePort} adapters {@link
 * com.drones.vision.perception.application.stream.DefaultStreamService} streams from — resolved the same way ({@link
 * VideoSourceRegistry#sourceFor}), opened under a transient, never-persisted {@link StreamId},
 * subscribed for exactly one frame, then closed. See {@link #probe} for the exact sequence.
 *
 * <h2>Why perception, not warehouse</h2>
 * A probe opens a {@link VideoSourcePort} and, for the telemetry side-check, a {@link
 * TelemetrySourcePort} — exactly the two kinds of connection a live stream opens, just for one
 * frame/sample instead of a running feed. That is ingest work, the same job {@link
 * com.drones.vision.perception.application.stream.DefaultStreamService} does for a real stream, not an
 * inventory concern. It lived under warehouse's {@code device} package until W1.6d
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md §15) only because a {@link Device} is what gets probed —
 * that made it look like device administration, but resolving and opening a source is perception's
 * job regardless of whether the device it names is ever saved. {@link ProbeFailedException} moved
 * with it for the same reason its javadoc gives: an exception belongs to the context that throws it.
 *
 * <h2>Telemetry detection</h2>
 * {@link ProbeResult#telemetryDetected()} is answered by building a synthetic, never-persisted
 * {@link Device} out of the same descriptor (both {@link Capability#VIDEO} and {@link
 * Capability#TELEMETRY}, since a probe request carries no capability of its own) and asking every
 * registered {@link TelemetrySourcePort} whether it claims that device; the first one that does is
 * opened and given a short bounded wait for one sample. This is honest for every protocol actually
 * registered against a {@code TelemetrySourcePort} today (only {@code mavlink}, which no {@code
 * VideoSourcePort} ever claims, so it never falsely fires for a video probe) and a fair
 * approximation for {@code sim} (adapter-simulation's {@code SimulatedTelemetrySource} claims any
 * device with the {@code TELEMETRY} capability and {@code sim} protocol regardless of URI — but
 * every simulated asset this codebase actually builds always bundles a video and a telemetry
 * device together, so "a sim probe implies telemetry will be available" is a fair read, not a lie).
 *
 * <h2>Failure messages</h2>
 * {@link #friendlyMessage} pattern-matches the failing {@link Throwable}'s own message text for a
 * handful of well-known substrings (401/unauthorized, connection refused, 404, timeout, HEVC/H.265)
 * and returns UX-DESIGN.md §5.1's specific, actionable phrasing; anything else falls back to the
 * raw message with a short prefix. This never imports an adapter-specific exception type (this
 * module may not depend on any adapter — ArchUnit-enforced) — it only ever reads {@link
 * Throwable#getMessage()}, which every adapter's {@code onError} already carries.
 */
public final class DefaultProbeService implements ProbeService {

    /** How long {@link #probe} waits for a video frame before giving up. */
    static final Duration DEFAULT_FRAME_TIMEOUT = Duration.ofSeconds(8);

    /** How long the telemetry side-check waits for one sample before concluding "not detected". */
    static final Duration DEFAULT_TELEMETRY_TIMEOUT = Duration.ofSeconds(2);

    private static final String TELEMETRY_WARNING = "No telemetry detected — OSD unavailable";

    private final VideoSourceRegistry videoSourceRegistry;
    private final List<TelemetrySourcePort> telemetrySources;
    private final Duration frameTimeout;
    private final Duration telemetryTimeout;

    public DefaultProbeService(VideoSourceRegistry videoSourceRegistry, List<TelemetrySourcePort> telemetrySources) {
        this(videoSourceRegistry, telemetrySources, DEFAULT_FRAME_TIMEOUT, DEFAULT_TELEMETRY_TIMEOUT);
    }

    /** Test seam: explicit, short timeouts so tests never wait out the production defaults. */
    DefaultProbeService(VideoSourceRegistry videoSourceRegistry, List<TelemetrySourcePort> telemetrySources,
                         Duration frameTimeout, Duration telemetryTimeout) {
        this.videoSourceRegistry = Objects.requireNonNull(videoSourceRegistry, "videoSourceRegistry must not be null");
        this.telemetrySources =
                List.copyOf(Objects.requireNonNull(telemetrySources, "telemetrySources must not be null"));
        this.frameTimeout = Objects.requireNonNull(frameTimeout, "frameTimeout must not be null");
        this.telemetryTimeout = Objects.requireNonNull(telemetryTimeout, "telemetryTimeout must not be null");
    }

    @Override
    public ProbeResult probe(StreamDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        VideoSourcePort videoSource = videoSourceRegistry.sourceFor(descriptor); // 400 if unrecognized
        VideoFrame frame = grabOneFrame(videoSource, descriptor);

        boolean telemetryDetected = detectTelemetry(descriptor);
        List<String> warnings = new ArrayList<>();
        if (!telemetryDetected) {
            warnings.add(TELEMETRY_WARNING);
        }
        return new ProbeResult(frame, codecFor(frame), fpsHint(descriptor), telemetryDetected, warnings);
    }

    /**
     * Opens {@code videoSource} under a fresh, transient {@link StreamId} (never registered, never
     * looked up again), requests exactly one frame, and closes the source again regardless of
     * outcome — the "test-before-save" seam itself.
     */
    private VideoFrame grabOneFrame(VideoSourcePort videoSource, StreamDescriptor descriptor) {
        StreamId probeStreamId = StreamId.random();
        CompletableFuture<VideoFrame> frameFuture = new CompletableFuture<>();
        try {
            Flow.Publisher<VideoFrame> publisher;
            try {
                publisher = videoSource.open(probeStreamId, descriptor);
            } catch (RuntimeException e) {
                throw new ProbeFailedException(friendlyMessage(e, descriptor), e);
            }
            publisher.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    subscription.request(1);
                }

                @Override
                public void onNext(VideoFrame item) {
                    frameFuture.complete(item);
                    subscription.cancel();
                }

                @Override
                public void onError(Throwable throwable) {
                    frameFuture.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    frameFuture.completeExceptionally(new ProbeFailedException(
                            "Stream ended before a frame was decoded — check the source"));
                }
            });
            return frameFuture.get(frameTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ProbeFailedException(
                    "No frame received within " + frameTimeout.toSeconds()
                            + "s — check the URI and network reachability");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ProbeFailedException probeFailed) {
                throw probeFailed;
            }
            throw new ProbeFailedException(friendlyMessage(cause, descriptor), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProbeFailedException("Probe was interrupted");
        } finally {
            videoSource.close(probeStreamId);
        }
    }

    /** See class javadoc's "Telemetry detection" section for the honesty argument behind this. */
    private boolean detectTelemetry(StreamDescriptor descriptor) {
        Device probeDevice = new Device(DeviceId.random(), "probe",
                Set.of(Capability.VIDEO, Capability.TELEMETRY), descriptor);
        TelemetrySourcePort telemetrySource = telemetrySources.stream()
                .filter(candidate -> candidate.supports(probeDevice))
                .findFirst()
                .orElse(null);
        if (telemetrySource == null) {
            return false;
        }
        CompletableFuture<Boolean> detected = new CompletableFuture<>();
        Flow.Publisher<Telemetry> publisher = telemetrySource.open(probeDevice);
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(Telemetry item) {
                detected.complete(true);
                subscription.cancel();
            }

            @Override
            public void onError(Throwable throwable) {
                detected.complete(false);
            }

            @Override
            public void onComplete() {
                detected.complete(false);
            }
        });
        try {
            return detected.get(telemetryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            return false; // never actually completed exceptionally above, but handled for completeness
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            telemetrySource.close(probeDevice.id());
        }
    }

    /**
     * Best-effort codec label from the frame's already-decoded {@link
     * com.drones.vision.perception.domain.model.PixelFormat} — see {@link ProbeResult#codec()}'s own javadoc
     * for why most formats honestly yield {@code null} here.
     */
    static String codecFor(VideoFrame frame) {
        return switch (frame.format()) {
            case JPEG -> "mjpeg";
            case H264_PACKET -> "h264";
            default -> null;
        };
    }

    /**
     * A configured/requested {@code fps} hint read straight from the descriptor's own options (the
     * only adapter that recognizes one today is adapter-simulation's {@code SimulatedVideoSource});
     * never a measurement — see {@link ProbeResult#fps()}'s own javadoc.
     */
    static Integer fpsHint(StreamDescriptor descriptor) {
        String raw = descriptor.options().get("fps");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Translates a raw adapter failure into one of UX-DESIGN.md §5.1's specific, actionable
     * messages by matching well-known substrings in {@link Throwable#getMessage()} — never by
     * inspecting the exception's concrete type, since that type belongs to an adapter module this
     * one may not depend on.
     */
    static String friendlyMessage(Throwable cause, StreamDescriptor descriptor) {
        String raw = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        String lower = raw.toLowerCase(Locale.ROOT);
        String protocolUpper = descriptor.protocol().toUpperCase(Locale.ROOT);

        if (lower.contains("401") || lower.contains("unauthorized")) {
            return protocolUpper + " 401 — camera rejected the password";
        }
        if (lower.contains("connection refused")) {
            int port = descriptor.uri() == null ? -1 : descriptor.uri().getPort();
            return port > 0
                    ? "Connection refused on :" + port + " — is " + protocolUpper + " enabled?"
                    : "Connection refused — is " + protocolUpper + " enabled on the target host?";
        }
        if (lower.contains("404") || lower.contains("not found")) {
            return "Stream not found at this path (404) — check the URI";
        }
        if (lower.contains("no route to host")) {
            return "No route to host — check the device's network address";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "Connection timed out — check the URI and network reachability";
        }
        if (lower.contains("hevc") || lower.contains("h265") || lower.contains("h.265")) {
            return "Codec H.265 not supported by this build";
        }
        if (lower.contains("decoder not found") || lower.contains("unsupported codec")
                || lower.contains("codec not currently supported")) {
            return "Codec not supported by this build: " + raw;
        }
        return "Could not connect: " + stripLibraryHints(raw);
    }

    /**
     * JavaCV appends developer-facing parentheticals to its messages — "(Has setFormat() been
     * called?)", "(For more details, make sure FFmpegLogCallback.set() has been called.)" —
     * that an operator reading a probe error can't act on. Keep the actionable core (which
     * includes the offending path/URI), drop the library internals.
     */
    private static String stripLibraryHints(String raw) {
        return raw.replaceAll("\\s*\\((?:Has |For more details)[^)]*\\)", "").strip();
    }
}
