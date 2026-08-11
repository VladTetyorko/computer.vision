package com.drones.vision.application.replay;

import com.drones.vision.flight.domain.model.AssetUsage;
import com.drones.vision.perception.domain.model.DetectionQuery;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.flight.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.events.domain.port.DetectionRepositoryPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ReplayService} default implementation: reads {@link AssetUsageRepositoryPort}, {@link
 * TelemetryRepositoryPort}, and {@link DetectionRepositoryPort}, windows and thins the result.
 *
 * <h2>{@code TelemetryRepositoryPort#findByUsage} gotcha</h2>
 * That port's {@code limit} selects the <b>earliest</b> samples for the usage, not the most recent
 * or a time-bounded slice (see its javadoc and {@code adapter-persistence}'s {@code
 * JpaTelemetryRepository}, which deliberately reproduces the in-memory reference's exact — if
 * surprising — semantics). There is no time-range query on this port at all. This class's
 * least-wrong workaround: fetch up to {@link #TELEMETRY_FETCH_LIMIT} samples (generous for a
 * typical demo-length flight) and filter/thin the requested window out of that fetch in memory. A
 * usage with more than {@value #TELEMETRY_FETCH_LIMIT} total samples will silently lose whatever
 * falls after the fetch cutoff, even if the caller's {@code to} asks for it — this is a real,
 * documented limitation, not a bug to "fix" here; the actual fix is a time-bounded query added to
 * the port, called out as a future contract-cleanup task in {@code adapter-persistence}'s and this
 * module's MODULE.md, not attempted in this task.
 *
 * <h2>Detections (docs/plans/done/MVP2-PLAN.md §R, R-a2)</h2>
 * Unlike telemetry, {@link DetectionRepositoryPort#query} <b>is</b> a real, time-bounded query
 * (filtered server-side/in-adapter, not fetched-then-filtered here) — so detections are queried
 * directly for the resolved window whenever {@link AssetUsage#streamId()} is non-{@code null}:
 * {@code new DetectionQuery(streamId, windowFrom, windowTo, null, DETECTION_FETCH_LIMIT)}. Both
 * bounds are passed straight through as the window's own {@code from}/{@code to} — deliberately,
 * because every existing {@code DetectionRepositoryPort} implementation treats {@code to} as
 * <b>inclusive</b> in practice (see {@code adapter-persistence}'s {@code JpaDetectionRepository}
 * javadoc/MODULE.md — {@code DetectionQuery#to()}'s own javadoc calls it exclusive, but neither
 * implementation actually honors that), which is exactly the inclusive-both-ends semantics this
 * window already uses for telemetry. A {@code null} {@code streamId()} (a legacy usage, or one
 * opened by an asset with no video device — see {@code AssetUsage}'s javadoc) yields an honestly
 * empty detections list: there is still no reliable cross-stream join to fall back to. Results come
 * back newest-first with {@code limit} already applied by the port, so this class re-sorts
 * ascending by {@link DetectionResult#capturedAt()} before thinning, mirroring telemetry's own
 * sort-then-thin order. A stream with more than {@link #DETECTION_FETCH_LIMIT} results inside the
 * requested window loses its <b>earliest</b> ones (the query's own newest-first-then-limit
 * behavior) — the mirror image of telemetry's earliest-biased truncation, and just as real a limit
 * for a very long/high-rate flight, though far less likely to bite at this cap.
 *
 * <h2>Recording (docs/plans/done/OPS-CORE-PLAN.md §R)</h2>
 * {@link #recordingFor(UsageId)} is a second, much smaller read: {@code streamId()} is again the
 * join key (empty when {@code null}), and the resolved {@code [startedAt, endedAt-or-now)} window
 * is handed straight to {@link StreamPublisherPort#playbackUrl} — no telemetry/detection fetch, no
 * downsampling, just a config-cheap lookup.
 */
public final class DefaultReplayService implements ReplayService {

    /**
     * Default cap the API layer falls back to when the caller omits {@code maxPoints} — the
     * {@link ReplayServiceSettings#defaults()} value, exposed here purely so same-package tests
     * (and any external reader still using this constant) can read it by name
     * (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3 config extraction); this instance's own working
     * value always comes from the {@link ReplayServiceSettings} supplied to its constructor.
     */
    public static final int DEFAULT_MAX_POINTS = ReplayServiceSettings.defaults().defaultMaxPoints();

    /** @see #DEFAULT_MAX_POINTS */
    static final int MAX_POINTS_CEILING = ReplayServiceSettings.defaults().maxPointsCeiling();

    /** @see #DEFAULT_MAX_POINTS */
    static final int TELEMETRY_FETCH_LIMIT = ReplayServiceSettings.defaults().fetchLimit();

    /** @see #DEFAULT_MAX_POINTS */
    static final int DETECTION_FETCH_LIMIT = ReplayServiceSettings.defaults().fetchLimit();

    private final AssetUsageRepositoryPort usageRepository;
    private final TelemetryRepositoryPort telemetryRepository;
    private final DetectionRepositoryPort detectionRepository;
    private final StreamPublisherPort streamPublisherPort;
    private final ReplayServiceSettings settings;

    /**
     * @param streamPublisherPort resolves the actual recording/clip-export URL for {@link
     *                            #recordingFor(UsageId)} (docs/plans/done/OPS-CORE-PLAN.md §R); required —
     *                            {@code vision-app} always wires a real bean here (either the
     *                            mediamtx-backed publisher or its no-op fallback), so there is no
     *                            nullable-collaborator case to handle.
     */
    public DefaultReplayService(AssetUsageRepositoryPort usageRepository,
                                 TelemetryRepositoryPort telemetryRepository,
                                 DetectionRepositoryPort detectionRepository,
                                 StreamPublisherPort streamPublisherPort) {
        this(usageRepository, telemetryRepository, detectionRepository, streamPublisherPort,
                ReplayServiceSettings.defaults());
    }

    /**
     * Same as the 4-argument constructor, plus an explicit {@link ReplayServiceSettings}
     * (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3 config extraction, {@code vision.application.replay.*})
     * instead of {@link ReplayServiceSettings#defaults()}.
     */
    public DefaultReplayService(AssetUsageRepositoryPort usageRepository,
                                 TelemetryRepositoryPort telemetryRepository,
                                 DetectionRepositoryPort detectionRepository,
                                 StreamPublisherPort streamPublisherPort,
                                 ReplayServiceSettings settings) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.telemetryRepository =
                Objects.requireNonNull(telemetryRepository, "telemetryRepository must not be null");
        this.detectionRepository =
                Objects.requireNonNull(detectionRepository, "detectionRepository must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
    }

    @Override
    public UsageTimeline timeline(UsageId usageId, Instant from, Instant to, int maxPoints) {
        Objects.requireNonNull(usageId, "usageId must not be null");
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be positive: " + maxPoints);
        }
        AssetUsage usage = usageRepository.findById(usageId)
                .orElseThrow(() -> new NoSuchElementException("Unknown usage: " + usageId.value()));

        Instant windowFrom = from != null ? from : usage.startedAt();
        Instant windowTo = to != null ? to : (usage.endedAt() != null ? usage.endedAt() : Instant.now());
        if (windowTo.isBefore(windowFrom)) {
            throw new IllegalArgumentException(
                    "to must not be before from: " + windowTo + " < " + windowFrom);
        }

        int effectiveMaxPoints = Math.min(maxPoints, settings.maxPointsCeiling());

        Instant finalWindowFrom = windowFrom;
        Instant finalWindowTo = windowTo;
        List<Telemetry> telemetry = telemetryRepository.findByUsage(usageId, settings.fetchLimit()).stream()
                .filter(sample -> !sample.at().isBefore(finalWindowFrom) && !sample.at().isAfter(finalWindowTo))
                .sorted(Comparator.comparing(Telemetry::at))
                .toList();

        List<DetectionResult> detections = detectionsFor(usage.streamId(), windowFrom, windowTo);

        return new UsageTimeline(usage, windowFrom, windowTo, thin(telemetry, effectiveMaxPoints),
                thin(detections, effectiveMaxPoints));
    }

    @Override
    public Optional<UsageRecording> recordingFor(UsageId usageId) {
        Objects.requireNonNull(usageId, "usageId must not be null");
        AssetUsage usage = usageRepository.findById(usageId)
                .orElseThrow(() -> new NoSuchElementException("Unknown usage: " + usageId.value()));
        if (usage.streamId() == null) {
            return Optional.empty();
        }
        Instant start = usage.startedAt();
        Instant end = usage.endedAt() != null ? usage.endedAt() : Instant.now();
        Duration duration = Duration.between(start, end);
        return streamPublisherPort.playbackUrl(usage.streamId(), start, duration)
                .map(url -> new UsageRecording(url, start, duration.getSeconds()));
    }

    /**
     * Queries this usage's detections for the resolved window — see this class's javadoc
     * ("Detections") for the inclusive-{@code to} and newest-first-then-limit reasoning.
     *
     * @param streamId  the usage's recorded stream, or {@code null} for a legacy/streamless usage
     * @param windowFrom resolved, inclusive lower bound
     * @param windowTo   resolved, inclusive upper bound
     * @return time-ordered (ascending) detections, or an empty list when {@code streamId} is {@code null}
     */
    private List<DetectionResult> detectionsFor(StreamId streamId, Instant windowFrom, Instant windowTo) {
        if (streamId == null) {
            return List.of();
        }
        return detectionRepository.query(new DetectionQuery(streamId, windowFrom, windowTo, null, settings.fetchLimit()))
                .stream()
                .sorted(Comparator.comparing(DetectionResult::capturedAt))
                .toList();
    }

    /**
     * Downsamples {@code items} (assumed already sorted by time) to at most {@code maxPoints}
     * entries via equidistant-index thinning, always keeping the first and last element.
     *
     * <p>When {@code items.size() <= maxPoints}, every item is kept unchanged (no thinning
     * needed). Otherwise, {@code maxPoints} indices spaced as evenly as possible across {@code
     * [0, items.size() - 1]} are selected, rounding each to the nearest integer index — this keeps
     * the result deterministic for a given input and {@code maxPoints}. {@code maxPoints == 1} is
     * a degenerate case with no room for both a first and a last point; it returns just the first
     * item.
     *
     * @param items     time-ordered items to thin; not mutated
     * @param maxPoints maximum number of items to return; must be positive
     * @param <T>       the item type
     * @return an immutable, time-ordered, thinned view of {@code items}
     */
    static <T> List<T> thin(List<T> items, int maxPoints) {
        int size = items.size();
        if (size <= maxPoints) {
            return items;
        }
        if (maxPoints <= 1) {
            return List.of(items.get(0));
        }
        List<T> thinned = new ArrayList<>(maxPoints);
        for (int i = 0; i < maxPoints; i++) {
            int index = (int) Math.round(i * (size - 1) / (double) (maxPoints - 1));
            thinned.add(items.get(index));
        }
        return List.copyOf(thinned);
    }
}
