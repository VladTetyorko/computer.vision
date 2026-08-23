package com.drones.vision.adapter.publishhls;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.StreamPublisherPort;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link StreamPublisherPort} that makes <b>mediamtx itself</b> dial an RTSP camera, instead of the
 * JVM decoding it and pushing frames (docs/plans/done/MEDIA-SOT-PLAN.md D3) — the "who publishes
 * video into mediamtx" half of the plan's two orthogonal switches (&sect;3). {@link PublisherRouter}
 * is what decides, per device, whether a stream reaches this class or {@link
 * MediamtxStreamPublisher}; this class assumes every device it is handed is proxy-eligible.
 *
 * <h2>Lifecycle</h2>
 * {@link #streamStarted} creates (or idempotently re-points, docs/plans/done/MEDIA-SOT-PLAN.md
 * &sect;5.3) a mediamtx path whose {@code source} is the device's own {@link
 * StreamDescriptor#uri()}, then — unless {@link MediamtxProxySettings#sourceOnDemand()} — polls
 * readiness up to {@link MediamtxProxySettings#readyTimeout()} before returning. A path that never
 * becomes ready fails the start call with a {@link MediamtxControlApiException} rather than handing
 * a viewer a URL that plays nothing (docs/plans/done/MEDIA-SOT-PLAN.md &sect;12's own named risk).
 * {@link #publish} is an intentional no-op: mediamtx, not this JVM, holds the frames. {@link
 * #streamEnded} deletes the path and — unlike {@link #streamStarted} — never lets a mediamtx failure
 * escape, mirroring {@link MediamtxStreamPublisher}'s "teardown must not block the caller" posture.
 *
 * <h2>D2 — the path name</h2>
 * The mediamtx path this class creates is named {@code streamId.value()}, exactly what {@link
 * MediamtxUrls}/{@link MediamtxPlaybackUrls} already assume for every URL — {@link #viewUrl}/{@link
 * #whepUrl}/{@link #playbackUrl} therefore need no logic beyond delegating to those same shared
 * helpers {@link MediamtxStreamPublisher} uses; a viewer, the recorder, and this publisher all agree
 * on one path name with zero coordination code.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s wiring
 * configuration.
 */
public final class MediamtxProxyPublisher implements StreamPublisherPort {

    private static final System.Logger LOG = System.getLogger(MediamtxProxyPublisher.class.getName());

    /**
     * Interval between readiness polls. Not a configuration knob — docs/plans/done/MEDIA-SOT-PLAN.md
     * &sect;5.5 only budgets the overall {@code ready-timeout} — 100ms keeps a typical camera dial
     * (well under a second in practice) responsive without hammering the Control API, and keeps this
     * class's own tests fast.
     */
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(100);

    private final MediamtxControlApi controlApi;
    private final URI hlsViewBase;
    private final URI whepViewBase;
    private final URI playbackViewBase;
    private final MediamtxProxySettings settings;

    /**
     * @param apiBase          mediamtx's Control API base, e.g. {@code http://localhost:19997} —
     *                         {@code vision.publish.mediamtx.api-base}
     * @param hlsViewBase      base HTTP URL of mediamtx's HLS egress; same meaning as {@link
     *                         MediamtxStreamPublisher}'s constructor argument of the same name
     * @param whepViewBase     base HTTP URL of mediamtx's WebRTC/WHEP egress; same meaning as {@link
     *                         MediamtxStreamPublisher}'s
     * @param playbackViewBase base HTTP URL of mediamtx's playback server, nullable; same meaning as
     *                         {@link MediamtxStreamPublisher}'s
     * @param settings         rtspTransport/readyTimeout/sourceOnDemand/API-credential tunables
     */
    public MediamtxProxyPublisher(URI apiBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase,
                                   MediamtxProxySettings settings) {
        this.hlsViewBase = Objects.requireNonNull(hlsViewBase, "hlsViewBase must not be null");
        this.whepViewBase = Objects.requireNonNull(whepViewBase, "whepViewBase must not be null");
        this.playbackViewBase = playbackViewBase;
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.controlApi = new MediamtxControlApi(apiBase, settings.apiUser(), settings.apiPassword());
    }

    @Override
    public void streamStarted(StreamId id, Device device) {
        if (id == null) {
            return;
        }
        Objects.requireNonNull(device, "device must not be null for a proxied stream");
        StreamDescriptor descriptor = device.stream();
        Objects.requireNonNull(descriptor, "device.stream() must not be null for a proxied stream");
        String pathName = id.value().toString();
        String sourceUrl = descriptor.uri().toString();

        LOG.log(System.Logger.Level.INFO, () -> "Creating mediamtx path " + pathName + " for stream " + id.value()
                + " with source " + sourceUrl + (device.name() != null ? " (" + device.name() + ")" : ""));
        controlApi.createOrUpdatePath(pathName, sourceUrl, settings.sourceOnDemand(), settings.rtspTransport());

        if (settings.sourceOnDemand()) {
            LOG.log(System.Logger.Level.INFO,
                    () -> "mediamtx path " + pathName + " created with sourceOnDemand=true; skipping the readiness "
                            + "poll -- mediamtx only dials " + sourceUrl + " once a viewer connects, so readiness "
                            + "has no meaning until then");
            return;
        }

        if (!pollUntilReady(pathName, settings.readyTimeout())) {
            throw new MediamtxControlApiException("mediamtx path " + pathName + " for stream " + id.value()
                    + " never became ready within " + settings.readyTimeout() + " after pointing it at source "
                    + sourceUrl + " -- refusing to return a viewer URL that would play nothing; check that the "
                    + "camera is reachable at that address and that rtspTransport='" + settings.rtspTransport()
                    + "' matches what it speaks");
        }
        LOG.log(System.Logger.Level.INFO,
                () -> "mediamtx path " + pathName + " for stream " + id.value() + " is ready");
    }

    /**
     * Intentional no-op (D3): mediamtx dials the camera itself, so no frame from the JVM's own video
     * path ever needs publishing here. See {@link #proxiesSource(Device)}.
     */
    @Override
    public void publish(StreamId id, VideoFrame frame) {
    }

    @Override
    public void streamEnded(StreamId id) {
        if (id == null) {
            return;
        }
        String pathName = id.value().toString();
        try {
            controlApi.deletePath(pathName);
            LOG.log(System.Logger.Level.INFO, () -> "Deleted mediamtx path " + pathName + " for stream " + id.value());
        } catch (MediamtxControlApiException e) {
            // Mirrors MediamtxStreamPublisher's "nothing escapes streamEnded" posture (see its class
            // javadoc, "Resilience"): teardown must not fail the caller just because mediamtx could
            // not be reached -- at worst the path is left behind for manual cleanup, not a viewer
            // stuck on a broken start call.
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to delete mediamtx path " + pathName + " for stream " + id.value(), e);
        }
    }

    @Override
    public Optional<URI> viewUrl(StreamId id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(MediamtxUrls.viewUrl(hlsViewBase, id)));
    }

    @Override
    public Optional<URI> whepUrl(StreamId id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(MediamtxUrls.whepUrl(whepViewBase, id)));
    }

    @Override
    public Optional<URI> playbackUrl(StreamId id, Instant start, Duration duration) {
        if (playbackViewBase == null || id == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        long durationSeconds = Math.round(duration.toMillis() / 1000.0);
        return Optional.of(URI.create(
                MediamtxPlaybackUrls.getUrl(playbackViewBase, id.value().toString(), start, durationSeconds)));
    }

    /**
     * {@inheritDoc} Always {@code true}: every stream this publisher handles is, by construction, one
     * mediamtx itself dials (D3) — {@link PublisherRouter} is what decides <i>whether</i> a given
     * device reaches this class in the first place, not this method.
     */
    @Override
    public boolean proxiesSource(Device device) {
        return true;
    }

    private boolean pollUntilReady(String pathName, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (controlApi.isReady(pathName)) {
                return true;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            long sleepMillis = Math.min(READY_POLL_INTERVAL.toMillis(), Math.max(1, remainingNanos / 1_000_000));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
