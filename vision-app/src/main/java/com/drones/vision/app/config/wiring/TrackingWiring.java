package com.drones.vision.app.config.wiring;

import com.drones.vision.api.dto.CvTrackerResponse;
import com.drones.vision.app.config.properties.VisionTrackingProperties;
import com.drones.vision.domain.model.TrackingConfig;
import com.drones.vision.domain.model.TrackingMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wires the tracking engine's deployment layer (docs/TRACKING-PLAN.md &sect;4.F,
 * docs/TRACKING-ORCHESTRATION.md &sect;4.1/&sect;4.3) — the two beans {@code vision-api} needs and
 * nothing else. Its own {@code @Configuration} class rather than more methods on {@link CvWiring},
 * for the same split-by-concern reason {@code PersistenceWiringConfiguration}/{@code
 * DiscoveryWiringConfiguration} exist: tracking is configured independently of whether the gRPC
 * detection channel is even built ({@code vision.cv.enabled}), and both beans below are
 * unconditional.
 *
 * <p>{@link VisionTrackingProperties#statsWindowSeconds()}/{@link
 * VisionTrackingProperties#trackRetentionSeconds()} are consumed elsewhere — {@code
 * ApplicationServiceWiring#streamPipelineSettings} maps them onto {@code StreamPipelineSettings},
 * which is where every other {@code vision-application} tunable already lands.
 *
 * <p>There is no {@code vision.tracking.enabled} flag, deliberately (docs/TRACKING-PLAN.md
 * &sect;5.G): {@code vision.cv.enabled} already kills CV wholesale, and {@code TrackingMode.OFF} is
 * a per-stream off-switch that is strictly better than a JVM-wide one.
 */
@Configuration
@EnableConfigurationProperties(VisionTrackingProperties.class)
public class TrackingWiring {

    /**
     * The tracking configuration new streams are seeded with — what {@code StreamController#start}
     * and {@code AssetController#startStream} merge a request's own {@code tracking} object onto
     * (docs/TRACKING-ORCHESTRATION.md &sect;4.1).
     *
     * <p>A plain domain value handed to two component-scanned controllers, the same "raw
     * collaborator, not a domain port" pattern {@code cvModelRoster}/{@code hlsProxyUpstreamBase}
     * already established — a deployment default has no service behind it to model as a port.
     *
     * <p><b>Seeds new streams only.</b> Nothing here can reach a running stream: its configuration
     * is its own state and changes only by {@code PATCH}. Restarting a stream is how a changed
     * default is picked up.
     *
     * <p>The three cadence/threshold knobs with no Java property ({@code redetectIouPercent},
     * {@code maxAgeFrames}, {@code minHits}) take {@link TrackingConfig}'s own documented defaults,
     * which are also the values the wire's {@code <=0} sentinels resolve to inside cv-service — one
     * number, one owner. {@code engineId} is left {@code ""}: "the server's default engine for this
     * mode", chosen by cv-service's registry, which is the only component that knows which engines
     * actually constructed on this box (docs/TRACKING-PLAN.md R11).
     *
     * @param properties the deployment's {@code vision.tracking.*} values
     * @return the seed configuration for newly started streams
     * @throws IllegalArgumentException if {@code vision.tracking.default-mode} is not a known mode —
     *                                  a startup failure, not a per-request surprise
     */
    @Bean
    public TrackingConfig streamStartTrackingDefaults(VisionTrackingProperties properties) {
        return new TrackingConfig(parseMode(properties.defaultMode()), "", properties.verifyEveryMillis(),
                properties.followFps(), TrackingConfig.DEFAULT_REDETECT_IOU_PERCENT,
                TrackingConfig.DEFAULT_MAX_AGE_FRAMES, TrackingConfig.DEFAULT_MIN_HITS, null);
    }

    /**
     * The tracker-engine roster {@code CvTrackersController} (component-scanned from {@code
     * vision-api}) serves at {@code GET /api/cv/trackers} (docs/TRACKING-PLAN.md &sect;4.F's frozen
     * wire contract) — the Tracking section's engine picker.
     *
     * <p>Deliberately a static, in-source constant, exactly like {@code CvWiring#cvModelRoster}: the
     * roster changes at deploy time, not runtime (docs/CV-CONTROL-PLAN.md &sect;D's frozen decision,
     * mirrored here). The three entries are the engines cv-service actually ships
     * (docs/TRACKING-PLAN.md &sect;5.B), with their <b>measured</b> per-frame costs — {@code
     * bytetrack} associates (Mode A), {@code lk}/{@code ncc} follow (Mode B), which is why {@code
     * modes} is a list and not a single value: cv-service has two engine protocols, and the picker
     * must never offer an associator for a follow (docs/TRACKING-ORCHESTRATION.md &sect;2.2).
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
