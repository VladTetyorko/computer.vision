package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.CorrectionSource;
import com.drones.vision.flight.domain.model.CorrectionStatus;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.flight.domain.port.TrackCorrectionLiveUpdatePort;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrackCorrectionRepositoryPort}/{@link TrackCorrectionLiveUpdatePort}/{@link
 * EventPublisherPort} are hand-rolled in-memory fakes (this package's dominant style, mirroring
 * {@code DefaultReadinessServiceTest} — no cross-context service is a dependency here, so nothing is
 * a Mockito mock). The clock is the package-private {@code Supplier<Instant>} test seam, stepped
 * deterministically per submission, never {@code Instant.now()}.
 */
class DefaultTrackCorrectionServiceTest {

    private static final AssetId ASSET_ID = AssetId.random();
    private static final UsageId USAGE_ID = UsageId.random();
    private static final Instant T0 = Instant.parse("2026-08-19T12:00:00Z");
    private static final GeoPosition BASE = new GeoPosition(50.0, 30.0, null);

    // gate: floor 5m, ceiling 50m, 3 consecutive fixes within 10m over a 10s window
    // divergence: sigma 3.0, 2 consecutive qualifying CONFIRMED fixes, 60s clear-after, 5m default raw radius
    private static TrackCorrectionSettings settings() {
        return new TrackCorrectionSettings(
                new TrackCorrectionSettings.GateSettings(5.0, 50.0, 3, 10.0, Duration.ofSeconds(10)),
                new TrackCorrectionSettings.DivergenceSettings(3.0, 2, Duration.ofSeconds(60), 5.0));
    }

    private static VisualFix acceptedFix(Instant frameAt, GeoPosition position, double radiusMeters,
                                          boolean evidenceEligible) {
        VisualFixEvidence evidence = new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, evidenceEligible, 5,
                50.0, evidenceEligible, 10.0, 5, 1.0);
        return new VisualFix(frameAt, position, 180.0, radiusMeters, 50.0, "kyiv-pozniaky", "17/76687/44230", "",
                evidence, 200, 300);
    }

    private static VisualFix refusedFix(Instant frameAt, String refusal) {
        VisualFixEvidence evidence = new VisualFixEvidence(2, 5, 1, 0.1, 0.05, 8.0, true, false, 1, 0.0, false, 0.0,
                0, 1.0);
        return new VisualFix(frameAt, null, null, null, null, "kyiv-pozniaky", "", refusal, evidence, 200, 300);
    }

    private static Telemetry telemetryAt(GeoPosition position) {
        return new Telemetry(DeviceId.random(), T0, position.latitude(), position.longitude(), 100.0, null, null,
                Map.of());
    }

    /** Advances by {@code step} on every read -- deterministic, never {@code Instant.now()}. */
    private static final class StepClock implements Supplier<Instant> {
        private Instant current;
        private final Duration step;

        StepClock(Instant start, Duration step) {
            this.current = start;
            this.step = step;
        }

        @Override
        public Instant get() {
            Instant now = current;
            current = current.plus(step);
            return now;
        }
    }

    private DefaultTrackCorrectionService service(FakeTrackCorrectionRepositoryPort repository,
                                                    FakeTrackCorrectionLiveUpdatePort liveUpdatePort,
                                                    FakeEventPublisherPort eventPublisher, Supplier<Instant> clock) {
        return new DefaultTrackCorrectionService(repository, liveUpdatePort, eventPublisher, settings(), clock);
    }

    // -- radius floor -----------------------------------------------------------------------

    @Test
    void radiusBelowFloorDowngradesToNoFixAndResetsAgreementWindow() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        FakeTrackCorrectionLiveUpdatePort liveUpdatePort = new FakeTrackCorrectionLiveUpdatePort();
        DefaultTrackCorrectionService service =
                service(repository, liveUpdatePort, new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        VisualFix fix = acceptedFix(T0, BASE, 2.0, true); // 2.0m < 5.0m floor
        TrackCorrection correction = service.submit(ASSET_ID, USAGE_ID, fix, telemetryAt(BASE));

        assertEquals(CorrectionStatus.NO_FIX, correction.status());
        assertNull(correction.position());
        assertEquals("radius 2.0m below floor 5.0m (not believed)", correction.refusal());
        assertEquals(1, repository.saved.size());
        assertEquals(1, liveUpdatePort.published.size());
    }

    @Test
    void missingRadiusOnAnAcceptedFixIsNotBelieved() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        VisualFixEvidence evidence = new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true,
                10.0, 5, 1.0);
        VisualFix fix = new VisualFix(T0, BASE, 180.0, null, 50.0, "kyiv-pozniaky", "17/76687/44230", "", evidence,
                200, 300);
        TrackCorrection correction = service.submit(ASSET_ID, USAGE_ID, fix, telemetryAt(BASE));

        assertEquals(CorrectionStatus.NO_FIX, correction.status());
        assertEquals("no radius reported for an accepted fix (not believed)", correction.refusal());
    }

    @Test
    void pythonRefusedFixPersistsAsNoFixWithPythonsOwnRefusal() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        VisualFix fix = refusedFix(T0, "LOW_TEXTURE");
        TrackCorrection correction = service.submit(ASSET_ID, USAGE_ID, fix, telemetryAt(BASE));

        assertEquals(CorrectionStatus.NO_FIX, correction.status());
        assertEquals("LOW_TEXTURE", correction.refusal());
        assertNull(correction.separationMeters());
        assertNull(correction.sigmaMeters());
    }

    // -- consecutive agreement ----------------------------------------------------------------

    @Test
    void fewerThanConfirmConsecutiveAgreeingFixesStaysProbable() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        TrackCorrection first = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true),
                telemetryAt(BASE));
        TrackCorrection second = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true),
                telemetryAt(BASE));

        assertEquals(CorrectionStatus.PROBABLE, first.status());
        assertEquals(CorrectionStatus.PROBABLE, second.status());
    }

    @Test
    void theNthConsecutiveAgreeingFixConfirms() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE));
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE));
        TrackCorrection third = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true),
                telemetryAt(BASE));

        assertEquals(CorrectionStatus.CONFIRMED, third.status());
    }

    @Test
    void aNoFixResetsTheAgreementWindowSoAFreshRunIsRequired() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE)); // 1 of 3
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE)); // 2 of 3
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 1.0, true), telemetryAt(BASE)); // NO_FIX -- resets

        // Without the reset, this would be the 4th consecutive agreeing fix (window slides to size 3) and
        // would already confirm here. The reset means it is only 1 of a fresh run of 3.
        TrackCorrection afterReset = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true),
                telemetryAt(BASE));
        assertEquals(CorrectionStatus.PROBABLE, afterReset.status());

        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE)); // 2 of fresh run
        TrackCorrection thirdOfFreshRun = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true),
                telemetryAt(BASE));

        assertEquals(CorrectionStatus.CONFIRMED, thirdOfFreshRun.status());
    }

    @Test
    void pairwiseDisagreementWithinTheWindowStaysProbable() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        GeoPosition far = new GeoPosition(50.01, 30.0, null); // ~1112m from BASE, well beyond the 10m agreement cap
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE));
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE));
        TrackCorrection third = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, far, 20.0, true),
                telemetryAt(far));

        assertEquals(CorrectionStatus.PROBABLE, third.status());
    }

    @Test
    void confirmWindowSpanExceededStaysProbable() {
        // Same position every time (pairwise agreement trivially holds) but 6s apart -- 3 fixes span 12s,
        // over the 10s confirm-window -- isolates the span gate from the pairwise-distance gate.
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(6)));

        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE));
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), telemetryAt(BASE));
        TrackCorrection third = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true),
                telemetryAt(BASE));

        assertEquals(CorrectionStatus.PROBABLE, third.status());
    }

    // -- radius ceiling / python evidence -----------------------------------------------------

    @Test
    void radiusAboveCeilingCapsAtProbableEvenWithFullAgreementAndEvidence() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 60.0, true), telemetryAt(BASE)); // 60m > 50m ceiling
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 60.0, true), telemetryAt(BASE));
        TrackCorrection third = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 60.0, true),
                telemetryAt(BASE));

        assertEquals(CorrectionStatus.PROBABLE, third.status());
    }

    @Test
    void unconvergedPythonEvidenceCapsAtProbableEvenWithFullAgreement() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, false), telemetryAt(BASE));
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, false), telemetryAt(BASE));
        TrackCorrection third = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, false),
                telemetryAt(BASE));

        assertEquals(CorrectionStatus.PROBABLE, third.status());
    }

    // -- separation / sigma computation (golden value) ----------------------------------------

    @Test
    void separationAndSigmaAreComputedFromTheGeodesicAndTheDefaultRawRadius() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        // 0.001 degree due north of BASE -- along a meridian the haversine central angle equals the
        // latitude delta exactly, so distance = EARTH_RADIUS_METERS * toRadians(0.001) = 111.1949...m
        GeoPosition fixPosition = new GeoPosition(50.001, 30.0, null);
        TrackCorrection correction = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE));

        assertEquals(111.1949, correction.separationMeters(), 0.01);
        // sigma = sqrt(defaultRawRadius^2 + fixRadius^2) = sqrt(5.0^2 + 20.0^2) = sqrt(425)
        assertEquals(Math.sqrt(425.0), correction.sigmaMeters(), 1e-9);
    }

    @Test
    void noRawTelemetrySampleLeavesSeparationAndSigmaAbsent() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        TrackCorrection correction = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, BASE, 20.0, true), null);

        assertNull(correction.rawPosition());
        assertNull(correction.separationMeters());
        assertNull(correction.sigmaMeters());
        assertFalse(correction.divergent());
    }

    // -- divergence integration ----------------------------------------------------------------

    @Test
    void risingEdgeOnTheSecondConsecutiveQualifyingConfirmedFixPublishesExactlyOneEvent() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        FakeEventPublisherPort eventPublisher = new FakeEventPublisherPort();
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                eventPublisher, new StepClock(T0, Duration.ofSeconds(1)));

        // fixPosition is ~222m from the raw telemetry position -- well beyond the sigma*combinedSigma
        // threshold (3.0 * sqrt(5^2+20^2) =~ 61.8m) -- and every fix agrees with itself, so status
        // reaches CONFIRMED on the 3rd submission (confirmConsecutive=3).
        GeoPosition fixPosition = new GeoPosition(50.002, 30.0, null);

        TrackCorrection first = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE));
        TrackCorrection second = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE));
        TrackCorrection third = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE)); // 1st CONFIRMED + qualifying fix -- arming, not yet latched
        TrackCorrection fourth = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE)); // 2nd CONFIRMED + qualifying fix -- rising edge

        assertEquals(CorrectionStatus.PROBABLE, first.status());
        assertEquals(CorrectionStatus.PROBABLE, second.status());
        assertEquals(CorrectionStatus.CONFIRMED, third.status());
        assertFalse(third.divergent());
        assertEquals(CorrectionStatus.CONFIRMED, fourth.status());
        assertTrue(fourth.divergent());

        assertEquals(1, eventPublisher.published.size());
        Event event = eventPublisher.published.get(0);
        assertEquals(EventType.POSITION_DIVERGENCE, event.type());
        assertNull(event.streamId());
        assertEquals(ASSET_ID.value().toString(), event.attributes().get("assetId"));
        assertEquals(USAGE_ID.value().toString(), event.attributes().get("usageId"));

        // A further qualifying CONFIRMED fix stays latched but does not republish.
        TrackCorrection fifth = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE));
        assertTrue(fifth.divergent());
        assertEquals(1, eventPublisher.published.size());
    }

    @Test
    void aSubsequentNoFixWhileLatchedStillReportsTheCarriedOverAlarmState() {
        DefaultTrackCorrectionService service = service(new FakeTrackCorrectionRepositoryPort(),
                new FakeTrackCorrectionLiveUpdatePort(), new FakeEventPublisherPort(),
                new StepClock(T0, Duration.ofSeconds(1)));

        GeoPosition fixPosition = new GeoPosition(50.002, 30.0, null);
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true), telemetryAt(BASE));
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true), telemetryAt(BASE));
        service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true), telemetryAt(BASE));
        TrackCorrection latched = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 20.0, true),
                telemetryAt(BASE));
        assertTrue(latched.divergent());

        // A radius-floor failure -- NO_FIX -- does not participate in the divergence rule at all, so the
        // already-latched alarm state is simply carried over onto this correction (informational).
        TrackCorrection noFix = service.submit(ASSET_ID, USAGE_ID, acceptedFix(T0, fixPosition, 1.0, true),
                telemetryAt(BASE));
        assertEquals(CorrectionStatus.NO_FIX, noFix.status());
        assertTrue(noFix.divergent());
        assertEquals(latched.divergentSince(), noFix.divergentSince());
    }

    // -- scoped reads ---------------------------------------------------------------------------

    @Test
    void forUsageUnderUnboundedScopeReturnsEveryCorrection() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        AssetId otherAsset = AssetId.random();
        repository.seed(USAGE_ID, correctionFor(ASSET_ID));
        repository.seed(USAGE_ID, correctionFor(otherAsset));
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        List<TrackCorrection> result = service.forUsage(USAGE_ID, 10, VisibilityScope.unbounded());

        assertEquals(2, result.size());
    }

    @Test
    void forUsageUnderAssignedAssetsScopeFiltersToTheAssignedAsset() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        AssetId otherAsset = AssetId.random();
        repository.seed(USAGE_ID, correctionFor(ASSET_ID));
        repository.seed(USAGE_ID, correctionFor(otherAsset));
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        List<TrackCorrection> result =
                service.forUsage(USAGE_ID, 10, VisibilityScope.assignedAssets(Set.of(ASSET_ID)));

        assertEquals(1, result.size());
        assertEquals(ASSET_ID, result.get(0).assetId());
    }

    @Test
    void latestUnderAssignedAssetsScopeIsAbsentForAnUnassignedAsset() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        repository.seed(USAGE_ID, correctionFor(ASSET_ID));
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        Optional<TrackCorrection> result =
                service.latest(ASSET_ID, VisibilityScope.assignedAssets(Set.of(AssetId.random())));

        assertTrue(result.isEmpty());
    }

    @Test
    void latestUnderGroupsScopeCurrentlyPassesThroughEveryAsset() {
        // Documented amendment (see DefaultTrackCorrectionService's own javadoc, "Scoped reads"): this
        // service has no warehouse dependency to resolve Ownership for a GROUPS-kind scope, so GROUPS is
        // pass-through here -- the real gate for that kind is vision-api's edge (D11).
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        repository.seed(USAGE_ID, correctionFor(ASSET_ID));
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        Optional<TrackCorrection> result = service.latest(ASSET_ID, VisibilityScope.groups(Set.of()));

        assertTrue(result.isPresent());
    }

    // -- prune ------------------------------------------------------------------------------

    @Test
    void pruneDelegatesToTheRepositoryAndReturnsItsCount() {
        FakeTrackCorrectionRepositoryPort repository = new FakeTrackCorrectionRepositoryPort();
        repository.deleteOlderThanReturn = 7;
        DefaultTrackCorrectionService service = service(repository, new FakeTrackCorrectionLiveUpdatePort(),
                new FakeEventPublisherPort(), new StepClock(T0, Duration.ofSeconds(1)));

        Instant horizon = Instant.parse("2026-08-01T00:00:00Z");
        int deleted = service.prune(horizon);

        assertEquals(7, deleted);
        assertEquals(horizon, repository.deleteOlderThanArgument);
    }

    // -- fixtures/fakes -------------------------------------------------------------------------

    private static TrackCorrection correctionFor(AssetId assetId) {
        VisualFixEvidence evidence = new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true,
                10.0, 5, 1.0);
        return new TrackCorrection(assetId, USAGE_ID, T0, T0, CorrectionStatus.CONFIRMED,
                CorrectionSource.VISUAL_HEAVY, BASE, 180.0, 20.0, 50.0, BASE, 0.0, 20.6, false, null,
                "kyiv-pozniaky", "17/76687/44230", "", evidence);
    }

    private static final class FakeTrackCorrectionRepositoryPort implements TrackCorrectionRepositoryPort {
        final List<TrackCorrection> saved = new ArrayList<>();
        final Map<UsageId, List<TrackCorrection>> byUsage = new HashMap<>();
        final Map<AssetId, TrackCorrection> latestByAsset = new HashMap<>();
        int deleteOlderThanReturn = 0;
        Instant deleteOlderThanArgument;

        void seed(UsageId usageId, TrackCorrection correction) {
            byUsage.computeIfAbsent(usageId, id -> new ArrayList<>()).add(correction);
            latestByAsset.put(correction.assetId(), correction);
        }

        @Override
        public void save(TrackCorrection correction) {
            saved.add(correction);
            byUsage.computeIfAbsent(correction.usageId(), id -> new ArrayList<>()).add(correction);
            latestByAsset.put(correction.assetId(), correction);
        }

        @Override
        public List<TrackCorrection> findByUsage(UsageId usageId, int limit) {
            List<TrackCorrection> all = byUsage.getOrDefault(usageId, List.of());
            return all.size() <= limit ? List.copyOf(all) : List.copyOf(all.subList(0, limit));
        }

        @Override
        public Optional<TrackCorrection> findLatest(AssetId assetId) {
            return Optional.ofNullable(latestByAsset.get(assetId));
        }

        @Override
        public int deleteOlderThan(Instant before) {
            deleteOlderThanArgument = before;
            return deleteOlderThanReturn;
        }

        @Override
        public int trimUsageToMostRecent(UsageId usageId, int maxRows) {
            throw new UnsupportedOperationException("not exercised by DefaultTrackCorrectionServiceTest");
        }
    }

    private static final class FakeTrackCorrectionLiveUpdatePort implements TrackCorrectionLiveUpdatePort {
        final List<TrackCorrection> published = new ArrayList<>();

        @Override
        public void publishCorrection(AssetId assetId, TrackCorrection correction) {
            published.add(correction);
        }
    }

    private static final class FakeEventPublisherPort implements EventPublisherPort {
        final List<Event> published = new ArrayList<>();

        @Override
        public void publish(Event event) {
            published.add(event);
        }
    }
}
