package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.CorrectionSource;
import com.drones.vision.flight.domain.model.CorrectionStatus;
import com.drones.vision.flight.domain.model.DivergenceRule;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.flight.domain.port.TrackCorrectionLiveUpdatePort;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BearingDistance;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GeoProjection;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;
import com.drones.vision.platform.VisibilityScope;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The one implementation of {@link TrackCorrectionService}.
 *
 * <h2>The Java-side gate (§4.3, the second half of D5)</h2>
 * Applied to every {@link VisualFix}, in order:
 * <ol>
 *   <li><b>Radius floor</b>: a Python-accepted fix ({@code fix.position() != null}) whose {@code
 *       radiusMeters} is absent or below {@link TrackCorrectionSettings.GateSettings#minRadiusMeters()}
 *       is <em>not believed</em> — downgraded to {@link CorrectionStatus#NO_FIX} with a Java-authored
 *       {@link TrackCorrection#refusal()}, even though Python itself refused nothing.</li>
 *   <li><b>Consecutive agreement</b>: every radius-floor-passing fix (regardless of what it will end
 *       up rated) is appended to a per-asset rolling window capped at {@code confirmConsecutive}
 *       entries. {@code CONFIRMED} needs a <em>full</em> window whose oldest-&gt;newest span fits
 *       {@code confirmWindow} and every pair is within {@code confirmAgreementMeters} of each other.
 *       A {@code NO_FIX} (Python- or Java-side) resets the window for that asset — "consecutive"
 *       is read literally: a gap breaks the run rather than being skipped over.</li>
 *   <li><b>Radius ceiling</b>: above {@code maxRadiusMeters}, at most {@link
 *       CorrectionStatus#PROBABLE} — independent of how many fixes agree.</li>
 *   <li><b>Python evidence</b>: {@code CONFIRMED} additionally requires {@code
 *       evidence.sequenceConverged() && evidence.cellCalibrated()}.</li>
 * </ol>
 * All three of agreement/ceiling/evidence must hold for {@link CorrectionStatus#CONFIRMED}; any one
 * missing (with a position still believed) is {@link CorrectionStatus#PROBABLE}.
 *
 * <h2>Divergence (§4.5)</h2>
 * {@code separationMeters}/{@code sigmaMeters} are computed — and reported — whenever both a
 * corrected and a raw position exist, regardless of status (informational for {@code PROBABLE} too).
 * Only whether the alarm <em>arms or clears</em> is gated to {@link CorrectionStatus#CONFIRMED} fixes,
 * delegated to {@link DivergenceRule} (built internally from {@link
 * TrackCorrectionSettings#divergence()} — not itself a constructor parameter, keeping this class's own
 * constructor at the frozen 4 arguments). A rising edge publishes exactly one {@link
 * EventType#POSITION_DIVERGENCE} {@link Event} via {@link EventPublisherPort} — production wiring
 * (`vision-app`'s {@code LiveUpdateEventPublisher} decorator) is what additionally forwards it to the
 * always-on {@code event} SSE topic; this class does not depend on {@code EventLiveUpdatePort} at all,
 * matching §3.5's frozen constructor shape.
 *
 * <p><b>Amendment (H2a, 2026-08-19, VISUAL-GEO-V2-PLAN.md §3.5)</b>: §4.5 specifies {@code rawRadius}
 * as "the raw fix's own HDOP-derived radius when present, else {@code
 * default-raw-radius-meters}" — but no HDOP-&gt;meters conversion exists anywhere in this codebase
 * today (checked: {@link Telemetry}/{@link com.drones.vision.kernel.FlightState} carry only the
 * dimensionless {@code hdop}, and the proto's {@code gps_radius_meters} is computed wire-side by a
 * component H2a does not touch). Rather than invent an unsourced conversion formula (a magic-number
 * violation), this wave always uses {@code divergence.default-raw-radius-meters}. A future wave
 * threading a real HDOP-derived radius through {@code GeoTelemetry}/{@code Telemetry} should replace
 * this with the real value; flagged here and in {@code contexts/vision-flight/MODULE.md} rather than
 * silently resolved.
 *
 * <h2>Scoped reads (§3.5, D11)</h2>
 * {@link #forUsage}/{@link #latest} take a {@link VisibilityScope} but this service has no warehouse
 * dependency to resolve an asset's {@code Ownership} from (the frozen constructor has 4 parameters,
 * none of them {@code AssetService}). {@link VisibilityScope.Kind#UNBOUNDED} and {@link
 * VisibilityScope.Kind#ASSIGNED_ASSETS} are fully decidable from {@code assetId} alone and are
 * enforced here; {@link VisibilityScope.Kind#GROUPS} is <em>not</em> — see {@link #isVisible} and D11's
 * own words ("REST reads are scoped normally — {@code VisibilityScope} at the {@code vision-api}
 * edge, 404 hides"). The real GROUPS-kind gate is {@code vision-api}'s job (resolving the asset via
 * warehouse's {@code AssetService#details(scope, assetId)} before ever calling this service) — flagged
 * as a plan amendment, not silently resolved.
 *
 * <h2>Threading</h2>
 * Per-asset agreement-ladder state lives in a {@link ConcurrentHashMap}, updated atomically via
 * {@link ConcurrentHashMap#compute}; {@link DivergenceRule} manages its own per-asset state the same
 * way. Concurrent {@link #submit} calls for different assets never contend.
 */
public final class DefaultTrackCorrectionService implements TrackCorrectionService {

    private final TrackCorrectionRepositoryPort repository;
    private final TrackCorrectionLiveUpdatePort liveUpdatePort;
    private final EventPublisherPort eventPublisher;
    private final TrackCorrectionSettings settings;
    private final Supplier<Instant> clock;
    private final DivergenceRule divergenceRule;

    private final ConcurrentHashMap<AssetId, List<AgreementEntry>> agreementWindows = new ConcurrentHashMap<>();

    public DefaultTrackCorrectionService(TrackCorrectionRepositoryPort repository,
                                          TrackCorrectionLiveUpdatePort liveUpdatePort,
                                          EventPublisherPort eventPublisher, TrackCorrectionSettings settings) {
        this(repository, liveUpdatePort, eventPublisher, settings, Instant::now);
    }

    /** Test seam: an injected clock, never {@code Instant.now()} on a test path. */
    DefaultTrackCorrectionService(TrackCorrectionRepositoryPort repository,
                                   TrackCorrectionLiveUpdatePort liveUpdatePort, EventPublisherPort eventPublisher,
                                   TrackCorrectionSettings settings, Supplier<Instant> clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.liveUpdatePort = Objects.requireNonNull(liveUpdatePort, "liveUpdatePort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.divergenceRule = new DivergenceRule(settings.divergence().sigma(),
                settings.divergence().consecutiveFixes(), settings.divergence().clearAfter());
    }

    @Override
    public TrackCorrection submit(AssetId assetId, UsageId usageId, VisualFix fix, Telemetry rawAtFrameTime) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(fix, "fix must not be null");

        Instant computedAt = clock.get();
        GeoPosition rawPosition = rawPositionOf(rawAtFrameTime);
        GateOutcome gated = applyJavaGates(assetId, fix, computedAt);

        Double separationMeters = null;
        Double sigmaMeters = null;
        if (gated.position() != null && rawPosition != null) {
            BearingDistance separation = GeoProjection.bearingDistance(rawPosition, gated.position());
            separationMeters = separation.distanceMeters();
            double rawRadius = settings.divergence().defaultRawRadiusMeters(); // see class javadoc amendment
            sigmaMeters = Math.sqrt(rawRadius * rawRadius + gated.radiusMeters() * gated.radiusMeters());
        }

        DivergenceRule.Outcome divergenceOutcome =
                divergenceRule.evaluate(assetId, gated.status(), separationMeters, sigmaMeters, computedAt);
        if (divergenceOutcome.risingEdge()) {
            publishDivergenceEvent(assetId, usageId, separationMeters, sigmaMeters, computedAt);
        }

        TrackCorrection correction = new TrackCorrection(assetId, usageId, fix.frameAt(), computedAt,
                gated.status(), CorrectionSource.VISUAL_HEAVY, gated.position(), gated.yawDegrees(),
                gated.radiusMeters(), gated.impliedAglMeters(), rawPosition, separationMeters, sigmaMeters,
                divergenceOutcome.divergent(), divergenceOutcome.divergentSince(), fix.regionId(), fix.tileId(),
                gated.refusal(), fix.evidence());

        repository.save(correction);
        liveUpdatePort.publishCorrection(assetId, correction);
        return correction;
    }

    @Override
    public List<TrackCorrection> forUsage(UsageId usageId, int limit, VisibilityScope scope) {
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        return repository.findByUsage(usageId, limit).stream()
                .filter(correction -> isVisible(correction.assetId(), scope))
                .toList();
    }

    @Override
    public Optional<TrackCorrection> latest(AssetId assetId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (!isVisible(assetId, scope)) {
            return Optional.empty();
        }
        return repository.findLatest(assetId);
    }

    @Override
    public int prune(Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        return repository.deleteOlderThan(before);
    }

    /** See this class's own javadoc "Scoped reads" section for why {@code GROUPS} is not enforced here. */
    private static boolean isVisible(AssetId assetId, VisibilityScope scope) {
        return switch (scope.kind()) {
            case UNBOUNDED, GROUPS -> true;
            case ASSIGNED_ASSETS -> scope.assignedAssets().contains(assetId);
        };
    }

    private static GeoPosition rawPositionOf(Telemetry telemetry) {
        if (telemetry == null || telemetry.latitude() == null || telemetry.longitude() == null) {
            return null; // an honest telemetry gap -- never fabricated
        }
        return new GeoPosition(telemetry.latitude(), telemetry.longitude(), telemetry.altitudeMeters());
    }

    private GateOutcome applyJavaGates(AssetId assetId, VisualFix fix, Instant computedAt) {
        if (fix.position() == null) {
            resetAgreementLadder(assetId);
            return GateOutcome.noFix(fix.refusal());
        }
        double minRadius = settings.gate().minRadiusMeters();
        if (fix.radiusMeters() == null || fix.radiusMeters() < minRadius) {
            resetAgreementLadder(assetId);
            String refusal = fix.radiusMeters() == null
                    ? "no radius reported for an accepted fix (not believed)"
                    : "radius " + fix.radiusMeters() + "m below floor " + minRadius + "m (not believed)";
            return GateOutcome.noFix(refusal);
        }

        boolean agrees = recordAgreementAndCheck(assetId, fix.position(), computedAt);
        boolean withinCeiling = fix.radiusMeters() <= settings.gate().maxRadiusMeters();
        boolean pythonEligible = fix.evidence().sequenceConverged() && fix.evidence().cellCalibrated();
        CorrectionStatus status = withinCeiling && pythonEligible && agrees
                ? CorrectionStatus.CONFIRMED
                : CorrectionStatus.PROBABLE;
        return new GateOutcome(status, fix.position(), fix.yawDegrees(), fix.radiusMeters(),
                fix.impliedAglMeters(), "");
    }

    private boolean recordAgreementAndCheck(AssetId assetId, GeoPosition position, Instant computedAt) {
        int windowSize = settings.gate().confirmConsecutive();
        List<AgreementEntry> window = agreementWindows.compute(assetId, (id, previous) -> {
            List<AgreementEntry> next = new ArrayList<>(previous == null ? List.of() : previous);
            next.add(new AgreementEntry(position, computedAt));
            while (next.size() > windowSize) {
                next.remove(0);
            }
            return List.copyOf(next);
        });
        if (window.size() < windowSize) {
            return false;
        }
        Duration span = Duration.between(window.get(0).at(), window.get(window.size() - 1).at());
        if (span.compareTo(settings.gate().confirmWindow()) > 0) {
            return false;
        }
        double maxAgreement = settings.gate().confirmAgreementMeters();
        for (int i = 0; i < window.size(); i++) {
            for (int j = i + 1; j < window.size(); j++) {
                double distance = GeoProjection.bearingDistance(window.get(i).position(), window.get(j).position())
                        .distanceMeters();
                if (distance > maxAgreement) {
                    return false;
                }
            }
        }
        return true;
    }

    private void resetAgreementLadder(AssetId assetId) {
        agreementWindows.remove(assetId);
    }

    private void publishDivergenceEvent(AssetId assetId, UsageId usageId, Double separationMeters,
                                         Double sigmaMeters, Instant now) {
        Map<String, String> attributes = Map.of(
                "assetId", assetId.value().toString(),
                "usageId", usageId.value().toString(),
                "separationMeters", String.valueOf(separationMeters),
                "sigmaMeters", String.valueOf(sigmaMeters));
        String message = "Visual track correction diverged from the reported position for asset " + assetId.value();
        Event event = new Event(UUID.randomUUID().toString(), null, now, EventType.POSITION_DIVERGENCE, message,
                attributes);
        eventPublisher.publish(event);
    }

    private record AgreementEntry(GeoPosition position, Instant at) {
    }

    private record GateOutcome(CorrectionStatus status, GeoPosition position, Double yawDegrees,
                                Double radiusMeters, Double impliedAglMeters, String refusal) {
        static GateOutcome noFix(String refusal) {
            return new GateOutcome(CorrectionStatus.NO_FIX, null, null, null, null, refusal);
        }
    }
}
