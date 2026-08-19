package com.drones.vision.map.application.track;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.ProjectedTrack;

import java.time.Instant;
import java.util.List;

/**
 * Folds a fixed camera's tracked objects into ground fixes, holds the live picture, decimates their
 * durable trail, and publishes {@link MapEvent.EntityType#TRACK} events (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md decision D3). One interface, one implementation ({@link
 * DefaultTrackProjectionService}).
 *
 * <h2>Lifecycle</h2>
 * A live {@link ProjectedTrack} is held in memory, keyed by {@code (assetId, trackId)}, and
 * republished on every {@link #project} tick that still sees it. It leaves the map — a {@link
 * MapEvent.Action#CLEARED} event, then removal from the held state — two ways: it drops out of the
 * {@code tracks} list a later {@link #project} call passes for the same asset (the perception track
 * book expired it), or the caller invokes {@link #clearAsset} directly (the owning stream stopped).
 * "Nothing lingers, nothing pretends" (D3).
 *
 * <h2>Honesty (D6)</h2>
 * A tracked object whose ray {@code FixedCameraGeo.project} refuses (below the horizon guard, beyond
 * the range ceiling, or too uncertain) publishes nothing for that tick — no event, no trail point —
 * and its previously-held live state, if any, is left exactly as it was; it is not thereby "seen" as
 * having disappeared from the track book, so it is not cleared either. See {@link #project}.
 *
 * <h2>Threading</h2>
 * Safe for concurrent use — {@link #project} runs on the projection runner's own scheduled cadence
 * while {@link #list} is read concurrently by live HTTP requests.
 */
public interface TrackProjectionService {

    /**
     * Projects every tracked object in {@code input.tracks()} through {@code input.pose()}, updating
     * the live picture and appending decimated trail points, then clears any previously-held live
     * track for {@code input.pose().assetId()} that no longer appears in {@code input.tracks()}.
     *
     * @param input the pose, the current track list, the frame dimensions, and when this tick was taken
     */
    void project(TrackProjectionInput input);

    /**
     * Clears every live track held for one asset — its owning stream stopped, so nothing further will
     * ever be seen for it. Publishes {@link MapEvent.Action#CLEARED} for each. A no-op (no events) for
     * an asset with no live tracks.
     *
     * @param assetId the asset whose live tracks to clear
     */
    void clearAsset(AssetId assetId);

    /**
     * Prunes every trail point, across every track, captured before {@code cutoff} — the D7 retention
     * prune, called by the projection runner on its own cadence. Does not touch the live picture.
     *
     * @param cutoff points captured before this instant are deleted
     */
    void pruneTrail(Instant cutoff);

    /**
     * Lists every live track on a layer {@code v} may {@link MapAccessPolicy#canView view}, each with
     * its stored trail — what {@code GET /api/map/tracks} rebuilds the picture from (D3).
     *
     * @param v who is asking
     * @return an immutable snapshot of visible live tracks with their trails
     */
    List<ProjectedTrackView> list(Viewer v);
}
