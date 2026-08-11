package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionEventState;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.drones.vision.perception.application.stream.DefaultStreamService;

/**
 * Debounces raw per-frame {@link DetectionResult}s into {@link DetectionEvent}s, one independent
 * state machine per tracked label (docs/plans/done/MVP2-PLAN.md §E, E-a): a label reaching {@link
 * EventRuleConfig#confidenceThreshold()} across {@link EventRuleConfig#consecutiveToOpen()}
 * consecutive results opens an event; the label then being absent (or below threshold) for {@link
 * EventRuleConfig#absenceToClose()} closes it.
 *
 * <p>One instance per running stream (constructed by {@link DefaultStreamService}, mirroring
 * {@link DetectionExtrapolator}'s per-pipeline lifetime), fed every completed result — including
 * empty ones, since an empty result is exactly what "absent" looks like — via {@link
 * #accept(DetectionResult)}, called from {@link StreamPipeline} the same way {@code
 * extrapolator.accept} already is.
 *
 * <h2>Timebase</h2>
 * Every timestamp this class produces comes from {@link DetectionResult#capturedAt()}, never
 * wall-clock time — consistent with {@link DetectionExtrapolator}'s own choice, and it means a
 * unit test can drive the whole open/close lifecycle off hand-picked {@code Instant}s with no
 * clock injection needed.
 *
 * <h2>Geolocation</h2>
 * {@link DetectionEvent#position()} is stamped exactly once, at the moment an event opens, from
 * {@link UsageTracker#latestPosition(AssetId)} — the freshest position the currently open usage
 * has accumulated from telemetry, read back rather than freshly queried (see that method's
 * javadoc for why this is the honest, cheap lookup: {@code TelemetryRepositoryPort} has no
 * "give me the latest sample" query shape to call instead). It is never updated afterward, even
 * while the event stays open and the asset keeps moving — live-tracking a position was judged out
 * of scope for a first cut (the map can still show "where is it now" independently). {@code
 * assetId}/{@code position} are simply {@code null} when {@link #usageTracker} is absent, the
 * device has no owning asset, or the asset has no currently open usage/position yet — never a
 * failure.
 *
 * <h2>Threading</h2>
 * {@link #accept} is {@code synchronized}, exactly like {@link DetectionExtrapolator#accept}/
 * {@link DetectionExtrapolator#at} — inference completions can land on arbitrary executor threads
 * out of order once more than one can be in flight, and per-label state must be read/mutated as
 * one atomic step.
 */
public final class DetectionEventEngine {

    private final StreamId streamId;
    private final DeviceId deviceId;
    private final EventRuleConfig config;
    private final UsageTracker usageTracker;
    private final DetectionEventRepositoryPort eventStore;

    private final Map<String, LabelState> stateByLabel = new HashMap<>();

    /**
     * @param usageTracker nullable — {@code null} means {@code assetId}/{@code position} are
     *                      always {@code null} on every event this engine opens, following the
     *                      same nullable-collaborator convention as {@link
     *                      DefaultStreamService}'s own {@code usageTracker}
     */
    public DetectionEventEngine(StreamId streamId, DeviceId deviceId, EventRuleConfig config, UsageTracker usageTracker,
                          DetectionEventRepositoryPort eventStore) {
        this.streamId = Objects.requireNonNull(streamId, "streamId must not be null");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.usageTracker = usageTracker; // nullable: no geolocation/asset resolution when absent
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    /**
     * Feeds one completed detection result through every tracked label's debounce state machine.
     *
     * @param result the completed result, empty or not
     */
    synchronized void accept(DetectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        for (String label : config.labels()) {
            LabelState state = stateByLabel.computeIfAbsent(label, l -> new LabelState());
            double confidence = peakConfidenceFor(result.detections(), label);
            if (confidence >= config.confidenceThreshold()) {
                onQualifying(label, state, result.capturedAt(), confidence);
            } else {
                onAbsent(state, result.capturedAt());
            }
        }
    }

    private void onQualifying(String label, LabelState state, Instant at, double confidence) {
        if (state.open != null) {
            state.open = state.open.withObservation(at, confidence);
            eventStore.save(state.open);
            return;
        }
        if (state.consecutiveCount == 0) {
            state.candidateFirstSeen = at;
        }
        state.consecutiveCount++;
        if (state.consecutiveCount >= config.consecutiveToOpen()) {
            state.open = open(label, state.candidateFirstSeen, at, confidence);
            eventStore.save(state.open);
        }
    }

    private void onAbsent(LabelState state, Instant at) {
        state.consecutiveCount = 0;
        state.candidateFirstSeen = null;
        if (state.open == null) {
            return;
        }
        Duration gap = Duration.between(state.open.lastSeen(), at);
        if (!gap.isNegative() && gap.compareTo(config.absenceToClose()) >= 0) {
            eventStore.save(state.open.closed());
            state.open = null;
        }
    }

    private DetectionEvent open(String label, Instant firstSeen, Instant lastSeen, double confidence) {
        AssetId assetId = usageTracker == null ? null : usageTracker.resolveAsset(deviceId).orElse(null);
        GeoPosition position = assetId == null ? null : usageTracker.latestPosition(assetId).orElse(null);
        return new DetectionEvent(DetectionEventId.random(), streamId, assetId, label, confidence, firstSeen,
                lastSeen, DetectionEventState.OPEN, position);
    }

    /** Highest confidence among {@code detections} for {@code label}, or a value below any valid
     *  confidence ({@code -1.0}) if {@code label} doesn't appear at all — never {@code 0.0} as the
     *  "absent" sentinel, since a real detection can legitimately carry {@code 0.0} confidence and
     *  a threshold of exactly {@code 0.0} must not treat "no detections" as qualifying. */
    private static double peakConfidenceFor(List<Detection> detections, String label) {
        double peak = -1.0;
        for (Detection d : detections) {
            if (label.equals(d.label()) && d.confidence() > peak) {
                peak = d.confidence();
            }
        }
        return peak;
    }

    /** Per-label debounce state; touched only from within {@link #accept}, itself synchronized. */
    private static final class LabelState {
        private int consecutiveCount;
        private Instant candidateFirstSeen;
        private DetectionEvent open;
    }
}
