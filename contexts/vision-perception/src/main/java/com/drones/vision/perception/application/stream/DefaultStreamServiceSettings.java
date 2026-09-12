package com.drones.vision.perception.application.stream;

import com.drones.vision.perception.application.pipeline.PullDetectionSettings;
import com.drones.vision.perception.application.pipeline.StreamPipelineSettings;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.domain.port.DetectionDemandPort;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.DetectionPolicyPort;
import com.drones.vision.perception.domain.port.TraceDemandPort;

import java.util.Objects;
import java.util.Optional;

/**
 * Every {@link DefaultStreamService} collaborator beyond its six mandatory ports, bundled per
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md Finding R1 — {@code DefaultStreamService}
 * previously grew one N-1-arg convenience constructor per wave (eight of them, a 12-arg canonical)
 * purely so pre-existing call sites kept compiling; this record replaces every one of those
 * overloads with a single canonical constructor plus this settings type.
 *
 * <p>{@link #usageTracker()}, {@link #detectionEventRepositoryPort()}, {@link
 * #liveUpdatePublisherPort()}, {@link #pullDetectionSettings()} and {@link #detectionDemandPort()}
 * are genuinely optional — the old code's own {@code null} meant "feature off" for each — so they
 * are {@link Optional} here rather than nullable positional arguments; {@link
 * DefaultStreamService}'s constructor unwraps each via {@code orElse(null)} into its existing
 * private nullable field, so internal behavior (including every {@code == null} check on the hot
 * {@code start}/{@code stop} path) is unchanged. {@link #pipelineSettings()} already had its own
 * non-null default ({@link StreamPipelineSettings#defaults()}) and stays a plain non-null field.
 *
 * @param usageTracker                empty means no-op usage tracking on every stream this service starts
 * @param detectionEventRepositoryPort empty means no debounced {@code DetectionEvent} tracking on
 *                                      any stream this service starts
 * @param liveUpdatePublisherPort      empty means no live-update announcements from any stream this
 *                                      service starts (docs/plans/done/REALTIME-PLAN.md §4)
 * @param pipelineSettings             threaded into this service's own source-reopen backoff and
 *                                      every {@link com.drones.vision.perception.application.pipeline.StreamPipeline}
 *                                      it starts; never {@code null}
 * @param pullDetectionSettings        empty means every stream this service starts uses push
 *                                      detection (docs/plans/done/MEDIA-SOT-PLAN.md wave M5, switch B)
 * @param detectionDemandPort          empty means the demand-poll task is never scheduled, so every
 *                                      stream is fail-open on demand (docs/plans/done/CV-DEMAND-PLAN.md §3.3)
 * @param detectionPolicyPort          empty means every stream's {@code DetectionPolicy} reads as
 *                                      {@code ON_VIEW} forever — no asset can ever widen its
 *                                      inference gate to {@code ALWAYS} (docs/plans/active/
 *                                      ALWAYS-ON-FLOW-PLAN.md wave D1). Consulted on the same
 *                                      demand-poll tick as {@link #detectionDemandPort} when either
 *                                      is present — the two are independently optional, so either
 *                                      one alone still schedules the poll task
 * @param traceDemandPort              empty means the demand-poll task never evaluates trace demand
 *                                      for any stream this service starts, so {@code
 *                                      PipelineConfig#trace()} stays at whatever value the stream
 *                                      started with (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 *                                      &sect;4.4); see {@link TraceDemandPort}'s own javadoc for why
 *                                      that is the correct "off" behavior rather than {@link
 *                                      #detectionDemandPort}'s fail-open-{@code true}. Consulted on
 *                                      the same demand-poll tick as {@link #detectionDemandPort}/
 *                                      {@link #detectionPolicyPort} — independently optional, so it
 *                                      alone is also enough to schedule the poll task
 * @param streamStateObserver           notified on every computed {@code StreamState} transition
 *                                      (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.2 C6);
 *                                      never {@code null} — {@link StreamStateObserver#NOOP} is the
 *                                      "nothing to do" value, not a nullable parameter (CLAUDE.md
 *                                      rule 10 / java-clean-code §3: no "null means off")
 */
public record DefaultStreamServiceSettings(Optional<UsageTracker> usageTracker,
                                            Optional<DetectionEventRepositoryPort> detectionEventRepositoryPort,
                                            Optional<DetectionLiveUpdatePort> liveUpdatePublisherPort,
                                            StreamPipelineSettings pipelineSettings,
                                            Optional<PullDetectionSettings> pullDetectionSettings,
                                            Optional<DetectionDemandPort> detectionDemandPort,
                                            Optional<DetectionPolicyPort> detectionPolicyPort,
                                            Optional<TraceDemandPort> traceDemandPort,
                                            StreamStateObserver streamStateObserver) {

    public DefaultStreamServiceSettings {
        Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        Objects.requireNonNull(detectionEventRepositoryPort, "detectionEventRepositoryPort must not be null");
        Objects.requireNonNull(liveUpdatePublisherPort, "liveUpdatePublisherPort must not be null");
        Objects.requireNonNull(pipelineSettings, "pipelineSettings must not be null");
        Objects.requireNonNull(pullDetectionSettings, "pullDetectionSettings must not be null");
        Objects.requireNonNull(detectionDemandPort, "detectionDemandPort must not be null");
        Objects.requireNonNull(detectionPolicyPort, "detectionPolicyPort must not be null");
        Objects.requireNonNull(traceDemandPort, "traceDemandPort must not be null");
        Objects.requireNonNull(streamStateObserver, "streamStateObserver must not be null");
    }

    /**
     * Reproduces the pre-R1 shortest (6-argument) constructor's behavior exactly: every optional
     * collaborator absent, default {@link StreamPipelineSettings}, {@link StreamStateObserver#NOOP}.
     */
    public static DefaultStreamServiceSettings defaults() {
        return new DefaultStreamServiceSettings(Optional.empty(), Optional.empty(), Optional.empty(),
                StreamPipelineSettings.defaults(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), StreamStateObserver.NOOP);
    }
}
