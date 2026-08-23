package com.drones.vision.app.devsupport;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.IngestState;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.Tile;
import com.drones.vision.perception.domain.port.ReferenceIndexPort;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ReferenceIndexPort} that never talks to cv-service — the flag-off fallback for {@code
 * vision.geo.visual.enabled=false} (docs/plans/done/VISUAL-GEO-V2-PLAN.md D9, "off means
 * invisible"). Needed so {@code GeoRegionController} (component-scanned, unconditional per {@code
 * CameraPoseController}'s precedent — see {@code VisualGeoWiringConfiguration}) can always get a
 * {@code ReferenceRegionService} bean built on top of <em>some</em> {@link ReferenceIndexPort}, even
 * when no gRPC channel to cv-service exists at all. Mirrors {@code
 * com.drones.vision.adapter.cvgrpc.NoopGeolocationPort}'s "a refusal, not silence" contract: {@link
 * #build} answers with exactly one terminal {@link IngestState#FAILED} update naming why, rather than
 * hanging or throwing from inside a reactive publisher.
 */
public final class NoopReferenceIndexPort implements ReferenceIndexPort {

    private static final System.Logger LOG = System.getLogger(NoopReferenceIndexPort.class.getName());

    @Override
    public Flow.Publisher<IngestProgress> build(RegionIngestSpec spec, Iterable<Tile> tiles) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(tiles, "tiles must not be null");
        LOG.log(System.Logger.Level.INFO,
                () -> "Visual geolocation is disabled; refusing region ingest for " + spec.regionId());
        return new SingleProgressPublisher(new IngestProgress(spec.regionId(), "done", 0, 0, IngestState.FAILED,
                "visual geolocation is disabled (vision.geo.visual.enabled=false)", null));
    }

    @Override
    public List<ReferenceIndexSummary> list() {
        return List.of();
    }

    @Override
    public void delete(String regionId) {
        // No-op: nothing was ever built.
    }

    /**
     * A minimal, Reactive-Streams-compliant {@link Flow.Publisher} that delivers exactly one item to
     * its (single) subscriber and completes — same shape as {@code NoopGeolocationPort}'s own
     * hand-rolled single-item publisher, deferring delivery until {@link Flow.Subscription#request}
     * actually asks for it (a {@link java.util.concurrent.SubmissionPublisher} would silently drop an
     * item submitted before a subscriber attaches).
     */
    private static final class SingleProgressPublisher implements Flow.Publisher<IngestProgress> {
        private final IngestProgress item;

        SingleProgressPublisher(IngestProgress item) {
            this.item = item;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super IngestProgress> subscriber) {
            subscriber.onSubscribe(new OneShotSubscription(subscriber, item));
        }
    }

    private static final class OneShotSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super IngestProgress> subscriber;
        private final IngestProgress item;
        private final AtomicBoolean settled = new AtomicBoolean(false);

        OneShotSubscription(Flow.Subscriber<? super IngestProgress> subscriber, IngestProgress item) {
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
