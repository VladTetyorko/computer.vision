package com.drones.vision.application.fleet;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DetectionEvent;
import com.drones.vision.domain.model.DetectionEventState;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FlightState;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.drones.vision.application.asset.AssetAttention;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatus;
import com.drones.vision.application.asset.AssetSummary;
import com.drones.vision.application.category.CategoryCounts;
import com.drones.vision.application.pipeline.DetectionEventEngine;
import com.drones.vision.application.pipeline.UsageTracker;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.application.stream.ActiveStream;
import com.drones.vision.application.stream.StreamService;

/**
 * The one implementation of {@link FleetSummaryService}.
 *
 * <h2>Composition, not new reads</h2>
 * Every fact this class reports already exists behind an existing collaborator: {@link
 * AssetService#assets(boolean)} for the fleet itself (with {@code categoryName}/{@code status}
 * already derived, soft-delete filtering already applied), {@link StreamService#streams()} for
 * which device is on which stream, {@link UsageTracker#latestTelemetry(AssetId)} for battery/
 * staleness, and {@link DetectionEventRepositoryPort#findRecent} for open detection events. This
 * class only joins them — see each private helper below.
 *
 * <h2>Bounds</h2>
 * <ul>
 *   <li>{@link #MAX_ASSETS_IN_SUMMARY} caps the per-asset list (docs/plans/done/MVP3-PLAN.md C-a's own done
 *       criterion: "capped/paged for 100+"); {@link FleetSummary#totalAssets()} always reports the
 *       true count, so a caller can detect truncation. Category counts are never capped — they are
 *       computed over every in-scope asset regardless of the per-asset list's cap, since a
 *       manager's "how many drones total" tile must stay honest past it.</li>
 *   <li>{@link #OPEN_EVENTS_SCAN_LIMIT} bounds how many of the fleet's most-recently-updated
 *       detection events are scanned to count each asset's open ones; see its own javadoc for the
 *       honest edge case this leaves.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Holds no mutable state — every read is answered fresh from the injected collaborators.
 */
public final class DefaultFleetSummaryService implements FleetSummaryService {

    /**
     * Hard cap on {@link FleetSummary#assets()}'s size (docs/plans/done/MVP3-PLAN.md C-a). Generously past the
     * "3-100 pilots" scale this cycle targets; a caller compares {@code assets().size()} against
     * {@link FleetSummary#totalAssets()} to detect truncation. True pagination (an offset/cursor
     * parameter) is deferred — nothing downstream has asked for more than a single generous cap
     * yet, and C-a's own done-criterion only requires "capped/paged for 100+".
     */
    static final int MAX_ASSETS_IN_SUMMARY = 500;

    /**
     * How many of the fleet's most-recently-updated {@link DetectionEvent}s (across every stream,
     * newest-first by {@code lastSeen}) are scanned to count each asset's currently {@code OPEN}
     * ones. Generous for this cycle's scale — {@code DetectionEventEngine}'s debounce rules keep
     * event volume naturally low — but honestly not exhaustive: an asset whose open event has
     * fallen out of the {@value #OPEN_EVENTS_SCAN_LIMIT} most-recently-updated events fleet-wide
     * (only plausible with many simultaneously very-active streams) would undercount.
     * {@link DetectionEventRepositoryPort} has no "count open events per asset" query shape to ask
     * for instead.
     */
    static final int OPEN_EVENTS_SCAN_LIMIT = 2000;

    private final AssetService assetService;
    private final StreamService streamService;
    private final UsageTracker usageTracker;
    private final DetectionEventRepositoryPort detectionEventRepositoryPort;
    private final int maxAssetsInSummary;
    private final int openEventsScanLimit;

    public DefaultFleetSummaryService(AssetService assetService, StreamService streamService,
                                       UsageTracker usageTracker,
                                       DetectionEventRepositoryPort detectionEventRepositoryPort) {
        this(assetService, streamService, usageTracker, detectionEventRepositoryPort, MAX_ASSETS_IN_SUMMARY,
                OPEN_EVENTS_SCAN_LIMIT);
    }

    /**
     * Same as the 4-argument constructor, plus explicit caps (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * &sect;1.3 config extraction, {@code vision.application.fleet.*}) instead of {@link
     * #MAX_ASSETS_IN_SUMMARY}/{@link #OPEN_EVENTS_SCAN_LIMIT}.
     */
    public DefaultFleetSummaryService(AssetService assetService, StreamService streamService,
                                       UsageTracker usageTracker,
                                       DetectionEventRepositoryPort detectionEventRepositoryPort,
                                       int maxAssetsInSummary, int openEventsScanLimit) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.detectionEventRepositoryPort =
                Objects.requireNonNull(detectionEventRepositoryPort, "detectionEventRepositoryPort must not be null");
        this.maxAssetsInSummary = maxAssetsInSummary;
        this.openEventsScanLimit = openEventsScanLimit;
    }

    @Override
    public FleetSummary summary(boolean includeArchived) {
        return summarize(assetService.assets(includeArchived));
    }

    @Override
    public FleetSummary summary(VisibilityScope scope, boolean includeArchived) {
        Objects.requireNonNull(scope, "scope must not be null");
        return summarize(assetService.assets(scope, includeArchived));
    }

    /** Aggregates an already-resolved (scoped or unscoped) asset-summary set into the read model. */
    private FleetSummary summarize(List<AssetSummary> summaries) {
        Map<DeviceId, StreamId> streamByDevice = streamByDevice();
        Map<AssetId, Integer> openEventCounts = openEventCounts();
        Instant now = Instant.now();

        List<AssetAttention> assets = summaries.stream()
                .sorted(Comparator.comparing(s -> s.asset().displayName(), String.CASE_INSENSITIVE_ORDER))
                .limit(maxAssetsInSummary)
                .map(s -> toAttention(s, streamByDevice, openEventCounts, now))
                .toList();

        return new FleetSummary(categoryCounts(summaries), assets, summaries.size());
    }

    private Map<DeviceId, StreamId> streamByDevice() {
        Map<DeviceId, StreamId> byDevice = new HashMap<>();
        for (ActiveStream active : streamService.streams()) {
            byDevice.put(active.deviceId(), active.streamId());
        }
        return byDevice;
    }

    private Map<AssetId, Integer> openEventCounts() {
        Map<AssetId, Integer> counts = new HashMap<>();
        for (DetectionEvent event : detectionEventRepositoryPort.findRecent(null, openEventsScanLimit)) {
            if (event.state() == DetectionEventState.OPEN && event.assetId() != null) {
                counts.merge(event.assetId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private AssetAttention toAttention(AssetSummary summary, Map<DeviceId, StreamId> streamByDevice,
                                        Map<AssetId, Integer> openEventCounts, Instant now) {
        Asset asset = summary.asset();
        StreamId streamId = asset.devices().stream()
                .map(streamByDevice::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        Telemetry latest = usageTracker.latestTelemetry(asset.id()).orElse(null);
        Double batteryPercent = latest == null ? null : latest.batteryPercent();
        Long telemetryAgeMs = latest == null ? null : Duration.between(latest.at(), now).toMillis();
        FlightState flightState = latest == null ? null : latest.flightState();
        String flightMode = flightState == null ? null : flightState.mode();
        Boolean armed = flightState == null ? null : flightState.armed();
        Boolean failsafe = flightState == null ? null : flightState.failsafe();

        return new AssetAttention(asset.id(), asset.displayName(), asset.category(), summary.categoryName(),
                asset.state(), summary.status() == AssetStatus.STREAMING, streamId, batteryPercent, telemetryAgeMs,
                openEventCounts.getOrDefault(asset.id(), 0), flightMode, armed, failsafe);
    }

    private List<CategoryCounts> categoryCounts(List<AssetSummary> summaries) {
        Map<CategoryId, Accumulator> byCategory = new LinkedHashMap<>();
        for (AssetSummary summary : summaries) {
            Asset asset = summary.asset();
            Accumulator acc =
                    byCategory.computeIfAbsent(asset.category(), id -> new Accumulator(summary.categoryName()));
            acc.total++;
            switch (asset.state()) {
                case ACTIVE -> acc.active++;
                case DEACTIVATED -> acc.deactivated++;
                case DELETED -> acc.deleted++;
            }
            if (summary.status() == AssetStatus.STREAMING) {
                acc.streaming++;
            }
        }
        return byCategory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(CategoryId::slug)))
                .map(e -> new CategoryCounts(e.getKey(), e.getValue().categoryName, e.getValue().total,
                        e.getValue().active, e.getValue().deactivated, e.getValue().deleted, e.getValue().streaming))
                .toList();
    }

    /** Per-category running totals while folding {@link #categoryCounts}; not a public read model. */
    private static final class Accumulator {
        private final String categoryName;
        private int total;
        private int active;
        private int deactivated;
        private int deleted;
        private int streaming;

        Accumulator(String categoryName) {
            this.categoryName = categoryName;
        }
    }
}
