package com.drones.vision.api.support;

import com.drones.vision.api.dto.StartStreamRequest;
import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.live.LiveAndPollTraceDemand;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.application.profile.EffectiveProfile;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;

import java.util.Objects;
import java.util.Optional;

/**
 * Bundles {@code StreamController}'s detection-related start-time concerns
 * (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.5/&sect;3.8, widened by docs/plans/active/CV-SETTINGS-PLAN.md
 * &sect;5.4/W2 deviation 3 for CV-profile resolution) behind one constructor parameter, deliberately:
 * that controller already sits at its own five-collaborator ceiling, and none of {@link
 * #defaultConfig()} (a single field read), {@link #touched(StreamId)} (a single delegated call), or
 * {@link #resolveStartConfig} (one more lookup plus a merge) is substantial enough on its own to
 * justify pushing the controller past this codebase's constructor-parameter ceiling
 * (.claude/skills/java-clean-code/SKILL.md &sect;3) — splitting the controller over three
 * single-method reads would be indirection for its own sake. This class exists to keep that
 * bundling honest and named, rather than unlabeled extra fields; growing it from two components to
 * five (rather than adding parameters to {@code StreamController}, already at its own ceiling) is
 * the sanctioned move that rule's own withdrawal note describes.
 *
 * <p>Plain class, constructed by {@code vision-app}'s wiring — not a {@code @Component} — mirroring
 * {@link SnapshotJpegEncoder}'s own precedent for a framework-free support class {@code vision-api}
 * holds but only {@code vision-app} can assemble (it alone knows whether {@code
 * vision.cv.demand.enabled} wired a real {@link LiveAndPollDetectionDemand} bean at all).
 *
 * @param defaultConfig       the deployment's default {@link PipelineConfig} for a newly started
 *                            device-level stream (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.8) — the
 *                            {@code platformDefault} {@link #resolveStartConfig} folds a bound {@code
 *                            CvProfile} down onto when nothing more specific matches, so {@code
 *                            vision.cv.detection-default-enabled} still reaches a started stream that
 *                            names no profile at any scope
 * @param demand              the demand port's concrete implementation, so {@link
 *                            #touched(StreamId)} can reach {@link
 *                            LiveAndPollDetectionDemand#touched(StreamId)} directly; {@code null}
 *                            when {@code vision.cv.demand.enabled=false} (the port bean is absent
 *                            entirely), in which case {@link #touched(StreamId)} is a no-op
 * @param cvProfileService    resolves a started device's asset's bound {@code CvProfile} — the
 *                            same asset &rarr; category &rarr; organization &rarr; platform fold
 *                            {@code GET /api/cv/profiles/effective} exposes as its own read
 * @param assetRepositoryPort looks up which {@link Asset} (if any) owns the device being started;
 *                            an unowned device (no asset at all) skips profile resolution entirely,
 *                            exactly as an unowned device already skips every other asset-scoped
 *                            concern in this controller
 * @param currentUser         the acting caller, so profile resolution is scoped the same way every
 *                            other read in this request is
 * @param traceDemand         the trace-demand port's concrete implementation (docs/plans/active/
 *                            CV-ORCHESTRATION-PLAN.md §4.4, wave W2), so {@link
 *                            #touchedTrace(StreamId)} can reach {@link
 *                            LiveAndPollTraceDemand#touched(StreamId)} directly; {@code null} on
 *                            the same condition {@link #demand} is (the demand gate not wired at
 *                            all), in which case {@link #touchedTrace(StreamId)} is a no-op — a
 *                            sixth component on this bundle rather than a seventh {@code
 *                            StreamController} constructor parameter, for the same reason {@link
 *                            #demand} is a component here and not a parameter there
 */
public record StreamDetectionSupport(PipelineConfig defaultConfig, LiveAndPollDetectionDemand demand,
                                      CvProfileService cvProfileService, AssetRepositoryPort assetRepositoryPort,
                                      CurrentUser currentUser, LiveAndPollTraceDemand traceDemand) {

    public StreamDetectionSupport {
        Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
        Objects.requireNonNull(cvProfileService, "cvProfileService must not be null");
        Objects.requireNonNull(assetRepositoryPort, "assetRepositoryPort must not be null");
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        // demand/traceDemand are nullable -- see this record's own javadoc
    }

    /**
     * Stamps {@code streamId} as polled just now (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.5) — a
     * no-op when {@link #demand()} is absent (the demand gate is not wired at all), the same
     * "nothing to do, quietly" posture every optional collaborator in this codebase takes.
     *
     * @param streamId the stream {@code GET /api/streams/{id}/detections} was just read for
     */
    public void touched(StreamId streamId) {
        if (demand != null) {
            demand.touched(streamId);
        }
    }

    /**
     * Stamps {@code streamId} as polled-for-trace just now (docs/plans/active/CV-ORCHESTRATION-PLAN.md
     * §4.4, wave W2) — a no-op when {@link #traceDemand()} is absent, the same posture {@link
     * #touched(StreamId)} already takes for its own port.
     *
     * @param streamId the stream {@code GET /api/streams/{id}/cv/trace} was just read for
     */
    public void touchedTrace(StreamId streamId) {
        if (traceDemand != null) {
            traceDemand.touched(streamId);
        }
    }

    /**
     * Resolves the {@link PipelineConfig} a newly started device-level stream should use
     * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.4, W2 deviation 3): folds {@code deviceId}'s
     * owning asset's bound {@code CvProfile} (asset &rarr; category &rarr; organization &rarr;
     * platform, {@link CvProfileService#effective}) underneath {@code body}'s own explicit
     * overrides — an explicit field on {@code body} always wins over whatever the profile fold
     * resolved, matching {@link StartStreamRequest#mergeOnto}'s existing "request beats default"
     * contract one level up.
     *
     * <p>A device with no owning asset at all (unowned) skips profile resolution and merges
     * {@code body} straight onto {@link #defaultConfig()}, byte-identical to before this wave —
     * there is no asset to resolve a profile for. This method does not itself enforce visibility:
     * {@code StreamController#start} still calls {@code StreamAccess#requireVisible} immediately
     * after, which is what actually turns an out-of-scope asset into a 404 in production; in a test
     * slice that mocks {@link CvProfileService} generically, that mock does not itself enforce
     * scope, so the existing 404 assertions there are satisfied by {@code requireVisible} instead —
     * both converge on the same outcome, just via different collaborators.
     *
     * @param deviceId the device being started
     * @param body     the raw request body — its own overrides are resolved twice against two
     *                 different bases ({@link #defaultConfig()} inside {@link
     *                 StartStreamRequest#mergeOnto} for the no-asset case, or the profile-folded
     *                 config for the owned case), never double-applied: exactly one of the two
     *                 merges below actually runs
     * @return the effective {@link PipelineConfig} to start the stream with
     */
    public PipelineConfig resolveStartConfig(DeviceId deviceId, StartStreamRequest body) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Optional<Asset> asset = assetRepositoryPort.findByDeviceId(deviceId);
        if (asset.isEmpty()) {
            return body.mergeOnto(defaultConfig);
        }
        EffectiveProfile effective =
                cvProfileService.effective(asset.get().id(), defaultConfig, currentUser.userId(), currentUser.scope());
        return body.mergeOnto(effective.config());
    }
}
