package com.drones.vision.adapter.publishhls;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.StreamPublisherPort;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link StreamPublisherPort} that picks, per device, between {@link MediamtxProxyPublisher} (D3 —
 * mediamtx dials the camera itself) and a plain JVM-side publisher such as {@link
 * MediamtxStreamPublisher} (docs/plans/done/MEDIA-SOT-PLAN.md &sect;3, switch A) — {@code
 * vision-app} wires exactly one {@link StreamPublisherPort} (this class), so neither {@code
 * StreamPipeline} nor {@code DefaultStreamService} needs to know two publishers exist.
 *
 * <h2>Routing rule</h2>
 * A device routes to the proxy publisher when, and only when, <b>both</b> hold: {@code
 * sourceProxyEnabled} (the {@code vision.publish.source-proxy.enabled} flag, threaded in at
 * construction — default {@code false}, D1) and the device's {@link StreamDescriptor#protocol()} is
 * {@code "rtsp"}. Every other device — a different protocol, a {@code null}/streamless device, or the
 * flag off — routes to the JVM publisher, exactly today's behaviour; D1 is what makes that the case
 * with zero special-casing here.
 *
 * <h2>Per-stream stickiness</h2>
 * {@link #proxiesSource(Device)} is evaluated purely from {@code (device, sourceProxyEnabled)} — the
 * application layer calls it <i>before</i> {@link #streamStarted} even runs, to decide whether to
 * open a {@code VideoSourcePort} at all, so it must stay side-effect-free. {@link #streamStarted}
 * routes the same way and then remembers which underlying publisher handled that {@link StreamId}, so
 * later calls that only carry a {@code StreamId} — {@link #publish}, {@link #streamEnded}, {@link
 * #viewUrl}, {@link #whepUrl}, {@link #playbackUrl} — are routed consistently even though the routing
 * rule itself needs a {@link Device} to evaluate. A {@code StreamId} this router never saw {@link
 * #streamStarted} for (a caller bug, or state lost across a restart) falls back to the JVM publisher —
 * this module's existing "degrade, don't throw" posture for calls outside the normal lifecycle.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s wiring
 * configuration.
 */
public final class PublisherRouter implements StreamPublisherPort {

    private static final String RTSP_PROTOCOL = "rtsp";

    private final StreamPublisherPort directPublisher;
    private final StreamPublisherPort proxyPublisher;
    private final boolean sourceProxyEnabled;
    private final Map<StreamId, StreamPublisherPort> routedTo = new ConcurrentHashMap<>();

    /**
     * @param directPublisher    handles every stream this router does not proxy — today's {@link
     *                           MediamtxStreamPublisher}
     * @param proxyPublisher     handles RTSP streams when {@code sourceProxyEnabled} — {@link
     *                           MediamtxProxyPublisher}
     * @param sourceProxyEnabled {@code vision.publish.source-proxy.enabled}; default {@code false} (D1)
     */
    public PublisherRouter(StreamPublisherPort directPublisher, StreamPublisherPort proxyPublisher,
                            boolean sourceProxyEnabled) {
        this.directPublisher = Objects.requireNonNull(directPublisher, "directPublisher must not be null");
        this.proxyPublisher = Objects.requireNonNull(proxyPublisher, "proxyPublisher must not be null");
        this.sourceProxyEnabled = sourceProxyEnabled;
    }

    @Override
    public void streamStarted(StreamId id, Device device) {
        StreamPublisherPort target = route(device);
        if (id != null) {
            routedTo.put(id, target);
        }
        target.streamStarted(id, device);
    }

    @Override
    public void publish(StreamId id, VideoFrame frame) {
        publisherFor(id).publish(id, frame);
    }

    @Override
    public void streamEnded(StreamId id) {
        if (id == null) {
            directPublisher.streamEnded(null);
            return;
        }
        StreamPublisherPort target = routedTo.remove(id);
        (target == null ? directPublisher : target).streamEnded(id);
    }

    @Override
    public Optional<URI> viewUrl(StreamId id) {
        return publisherFor(id).viewUrl(id);
    }

    @Override
    public Optional<URI> whepUrl(StreamId id) {
        return publisherFor(id).whepUrl(id);
    }

    @Override
    public Optional<URI> playbackUrl(StreamId id, Instant start, Duration duration) {
        return publisherFor(id).playbackUrl(id, start, duration);
    }

    /** Delegates to whichever publisher {@link #route} would pick for {@code device}. */
    @Override
    public boolean proxiesSource(Device device) {
        return route(device).proxiesSource(device);
    }

    private StreamPublisherPort publisherFor(StreamId id) {
        if (id == null) {
            return directPublisher;
        }
        return routedTo.getOrDefault(id, directPublisher);
    }

    private StreamPublisherPort route(Device device) {
        if (sourceProxyEnabled && device != null && device.stream() != null
                && RTSP_PROTOCOL.equals(device.stream().protocol())) {
            return proxyPublisher;
        }
        return directPublisher;
    }
}
