package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.perception.domain.port.PulledGeolocationPort;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link PulledGeolocationPort} that never talks to cv-service — the flag-off fallback for {@code
 * vision.geo.visual.enabled=false} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §0/D9, "off means
 * invisible"). Whichever wiring wave (H5) decides how {@code vision.geo.visual.enabled} selects a
 * bean is free to either omit a {@link PulledGeolocationPort} bean entirely when disabled (most of
 * this feature's beans are simply absent) or wire this class instead — this class exists for
 * callers that need <em>a</em> port instance regardless (e.g. a composition root that always injects
 * one).
 *
 * <h2>Contract</h2>
 * {@link #open} returns a publisher that delivers exactly one {@link VisualFix} — {@code position ==
 * null}, {@code refusal} naming why the feature is off — then completes normally. This mirrors
 * {@link VisualFix}'s own "a refusal, not an error" contract (never {@code onError}): a caller that
 * subscribes and requests at least one item learns immediately, and unambiguously, that no real
 * localization will ever happen for this session — not silence, not a hang. {@link #telemetry}/{@link
 * #close} are no-ops; this class holds no per-stream state.
 */
public final class NoopGeolocationPort implements PulledGeolocationPort {

    private static final System.Logger LOG = System.getLogger(NoopGeolocationPort.class.getName());

    @Override
    public Flow.Publisher<VisualFix> open(StreamId id, URI sourceUrl, GeoSessionConfig config) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
        Objects.requireNonNull(config, "config must not be null");
        LOG.log(System.Logger.Level.INFO,
                () -> "Visual geolocation is disabled; refusing session for stream " + id);
        return new SingleFixPublisher(disabledFix());
    }

    @Override
    public void telemetry(StreamId id, Telemetry telemetry) {
        // No-op: this port holds no per-stream state to update.
    }

    @Override
    public void close(StreamId id) {
        // No-op: nothing was ever opened.
    }

    private static VisualFix disabledFix() {
        return new VisualFix(
                Instant.now(),
                null,
                null,
                null,
                null,
                "",
                "",
                "visual geolocation is disabled (vision.geo.visual.enabled=false)",
                new VisualFixEvidence(0, 0, 0, 0.0, 0.0, 0.0, false, false, 0, 0.0, false, 0.0, 0, 0.0),
                0L,
                0L);
    }

    /**
     * A minimal, Reactive-Streams-compliant {@link Flow.Publisher} that delivers exactly one item to
     * its (single) subscriber and completes — {@link java.util.concurrent.SubmissionPublisher} has
     * no synchronous single-item convenience, and submitting before a subscriber attaches would
     * silently drop the item, so this hand-rolled publisher defers delivery until {@link
     * Flow.Subscription#request} actually asks for it.
     */
    private static final class SingleFixPublisher implements Flow.Publisher<VisualFix> {
        private final VisualFix item;

        SingleFixPublisher(VisualFix item) {
            this.item = item;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super VisualFix> subscriber) {
            subscriber.onSubscribe(new OneShotSubscription(subscriber, item));
        }
    }

    private static final class OneShotSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super VisualFix> subscriber;
        private final VisualFix item;
        private final AtomicBoolean settled = new AtomicBoolean(false);

        OneShotSubscription(Flow.Subscriber<? super VisualFix> subscriber, VisualFix item) {
            this.subscriber = subscriber;
            this.item = item;
        }

        @Override
        public void request(long n) {
            if (n > 0 && settled.compareAndSet(false, true)) {
                subscriber.onNext(item);
                subscriber.onComplete();
            }
        }

        @Override
        public void cancel() {
            settled.set(true);
        }
    }
}
