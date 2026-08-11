package com.drones.vision.app.config.wiring;

import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.app.config.properties.VisionTrackingProperties;
import com.drones.vision.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.domain.model.TrackingMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wires the tracking engine's deployment layer (docs/plans/done/TRACKING-PLAN.md &sect;4.F,
 * docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1/&sect;4.3) — the engine roster bean {@code vision-api}
 * needs, plus the mapping of {@code vision.tracking.*} onto the stream-start seed. Its own
 * {@code @Configuration} class rather than more methods on {@link CvWiring}, for the same
 * split-by-concern reason {@code PersistenceWiringConfiguration}/{@code
 * DiscoveryWiringConfiguration} exist: tracking is configured independently of whether the gRPC
 * detection channel is even built ({@code vision.cv.enabled}).
 *
 * <p><b>Every {@code vision.tracking.*} value lands on {@code StreamPipelineSettings}</b>, which
 * {@code ApplicationServiceWiring#streamPipelineSettings} builds and hands to {@code
 * DefaultStreamService}: {@link VisionTrackingProperties#statsWindowSeconds()}/{@link
 * VisionTrackingProperties#trackRetentionSeconds()} configure the per-stream read models, and
 * {@link #streamStartTrackingSeed} configures what a new stream starts with. That is why this class
 * exposes only one bean — a deployment default belongs where the streams are started, not at the
 * REST edge.
 *
 * <p>There is no {@code vision.tracking.enabled} flag, deliberately (docs/plans/done/TRACKING-PLAN.md
 * &sect;5.G): {@code vision.cv.enabled} already kills CV wholesale, and {@code TrackingMode.OFF} is
 * a per-stream off-switch that is strictly better than a JVM-wide one.
 */
@Configuration
@EnableConfigurationProperties(VisionTrackingProperties.class)
public class TrackingWiring {

    /**
     * The tracking seed new streams are started on (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1) — what
     * a start request's own {@code tracking} object folds onto, and what a start request that says
     * nothing gets outright.
     *
     * <p>A {@link TrackingConfigPatch} rather than a whole {@code TrackingConfig}, because a
     * deployment states <b>only the knobs it owns</b>: the three with no Java property ({@code
     * redetectIouPercent}, {@code maxAgeFrames}, {@code minHits}) stay {@code null} and fall through
     * to {@code TrackingConfig}'s own literals in {@code vision-domain}, which are also the values
     * the wire's {@code <=0} sentinels resolve to inside cv-service — one number, one owner, rather
     * than this method restating a default it does not own. {@code engineId} is left {@code null} for
     * the same reason: the engine is cv-service's registry's choice, the only component that knows
     * which engines actually constructed on this box (docs/plans/done/TRACKING-PLAN.md R11).
     *
     * <p><b>Not a bean, and not injected into a controller.</b> The seed is folded by {@code
     * DefaultStreamService#start} — the one point every start path (device, asset, simulation, demo
     * fleet) passes through — so it rides {@code StreamPipelineSettings}, which {@link
     * ApplicationServiceWiring} already builds from these same properties. Seeding at the REST edge
     * instead used to leave simulation-started streams with no deployment default at all, and took
     * {@code AssetController} to six constructor arguments.
     *
     * <p><b>Seeds new streams only.</b> Nothing here can reach a running stream: its configuration
     * is its own state and changes only by {@code PATCH}. Restarting a stream is how a changed
     * default is picked up.
     *
     * @param properties the deployment's {@code vision.tracking.*} values
     * @return what the deployment states about tracking for newly started streams
     * @throws IllegalArgumentException if {@code vision.tracking.default-mode} is not a known mode —
     *                                  a startup failure, not a per-request surprise
     */
    static TrackingConfigPatch streamStartTrackingSeed(VisionTrackingProperties properties) {
        return new TrackingConfigPatch(parseMode(properties.defaultMode()), null, properties.verifyEveryMillis(),
                properties.followFps(), null, null, null, null);
    }

    /**
     * The tracker-engine roster {@code CvTrackersController} (component-scanned from {@code
     * vision-api}) serves at {@code GET /api/cv/trackers} (docs/plans/done/TRACKING-PLAN.md &sect;4.F's frozen
     * wire contract) — the Tracking section's engine picker.
     *
     * <p>Deliberately a static, in-source constant, exactly like {@code CvWiring#cvModelRoster}: the
     * roster changes at deploy time, not runtime (docs/plans/done/CV-CONTROL-PLAN.md &sect;D's frozen decision,
     * mirrored here). The three entries are the engines cv-service actually ships
     * (docs/plans/done/TRACKING-PLAN.md &sect;5.B), with their <b>measured</b> per-frame costs — {@code
     * bytetrack} associates (Mode A), {@code lk}/{@code ncc} follow (Mode B), which is why {@code
     * modes} is a list and not a single value: cv-service has two engine protocols, and the picker
     * must never offer an associator for a follow (docs/extracts/TRACKING-ORCHESTRATION.md &sect;2.2).
     *
     * <p>A roster entry is a claim about what this deployment ships, not a promise that the engine
     * constructed on this box; {@code tracker_engine_id} on every response is the ground truth the
     * UI displays (R11).
     *
     * @return the roster, in display order
     */
    @Bean
    public List<CvTrackerResponse> cvTrackerRoster() {
        return List.of(
                new CvTrackerResponse("bytetrack", "ByteTrack (multi-object)", List.of("ASSOCIATE"), false,
                        "~0.8 ms/frame"),
                new CvTrackerResponse("lk", "Optical flow (fast follow)", List.of("FOLLOW"), false, "~0.4 ms/frame"),
                new CvTrackerResponse("ncc", "Template match (robust follow)", List.of("FOLLOW"), false,
                        "~0.6 ms/frame"));
    }

    /**
     * Case-insensitive {@link TrackingMode} lookup, listing the valid values on failure — the same
     * idiom the API edge's own DTOs use, so a typo in a property file fails the same way a typo in a
     * request body does.
     */
    private static TrackingMode parseMode(String value) {
        return Arrays.stream(TrackingMode.values())
                .filter(candidate -> candidate.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown vision.tracking.default-mode: " + value
                        + ". Valid values: "
                        + Arrays.stream(TrackingMode.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }
}
