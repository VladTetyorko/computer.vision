package com.drones.vision.api.dto;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.application.track.ProjectedTrackView;
import com.drones.vision.map.domain.model.ProjectedTrack;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link ProjectedTrack} — one row of {@code GET /api/map/tracks}, and the
 * {@code track} field of a live {@code TRACK} {@link MapEventPayload} on the {@code map} SSE topic
 * (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5's frozen wire contract). One record serves three
 * shapes, distinguished by which factory built it:
 * <ul>
 *   <li>{@link #from(ProjectedTrackView)} — {@code GET /api/map/tracks}: every field populated,
 *       {@code trail} included.</li>
 *   <li>{@link #live(ProjectedTrack)} — a live {@code created}/{@code updated} event: every field
 *       populated except {@code trail} (a reload's {@code GET} is the trail's own source).</li>
 *   <li>{@link #cleared(AssetId, long)} — a live {@code cleared} event: only {@code assetId}/{@code
 *       trackId}; every other field is genuinely absent, not merely unresolved.</li>
 * </ul>
 * {@code @JsonInclude(NON_NULL)} with every field but {@code assetId}/{@code trackId} boxed is what
 * makes the {@code cleared} shape possible from the same record (§5's own explicit requirement).
 *
 * @param assetId           the camera asset this track was seen by, as a canonical UUID string
 * @param trackId           the perception {@code TrackBook} id — stable for this track's lifetime
 * @param label             the tracked object's current label (e.g. {@code "car"})
 * @param layerId           the layer this track publishes to, as a canonical UUID string
 * @param latitude          the current projected ground point's latitude
 * @param longitude         the current projected ground point's longitude
 * @param rangeMeters       ground range from the camera to the fix
 * @param errorRadiusMeters the D6 uncertainty radius — drawn as a circle under the track dot,
 *                          always
 * @param updatedAt         when this fix was computed
 * @param trail             stored trail points, oldest first; absent (not empty) outside {@link
 *                          #from(ProjectedTrackView)}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectedTrackResponse(String assetId, long trackId, String label, String layerId, Double latitude,
                                      Double longitude, Double rangeMeters, Double errorRadiusMeters,
                                      Instant updatedAt, List<TrackPointResponse> trail) {

    /**
     * Maps a {@link ProjectedTrackView} (a live track plus its durable trail) to the full {@code GET
     * /api/map/tracks} row shape.
     *
     * @param view the track and its stored trail
     * @return the response row for {@code view}
     */
    public static ProjectedTrackResponse from(ProjectedTrackView view) {
        List<TrackPointResponse> trail = view.trail().stream().map(TrackPointResponse::from).toList();
        return build(view.track(), trail);
    }

    /**
     * Maps a live {@link ProjectedTrack} to a {@code created}/{@code updated} live-event {@code
     * track} field — every field but {@code trail}, which is deliberately absent here (D3: the
     * live channel is not the trail's source).
     *
     * @param track the current live fix
     * @return the live-event {@code track} field for {@code track}
     */
    public static ProjectedTrackResponse live(ProjectedTrack track) {
        return build(track, null);
    }

    /**
     * Builds the stripped {@code cleared} live-event {@code track} field — only {@code assetId} and
     * {@code trackId}, every other field {@code null} and so omitted from the wire (§5's own second
     * live-channel example).
     *
     * @param assetId the asset the cleared track belonged to
     * @param trackId the cleared track's id
     * @return the stripped {@code cleared} shape
     */
    public static ProjectedTrackResponse cleared(AssetId assetId, long trackId) {
        return new ProjectedTrackResponse(assetId.value().toString(), trackId, null, null, null, null, null, null,
                null, null);
    }

    private static ProjectedTrackResponse build(ProjectedTrack track, List<TrackPointResponse> trail) {
        return new ProjectedTrackResponse(
                track.assetId().value().toString(),
                track.trackId(),
                track.label(),
                track.layerId().value().toString(),
                track.position().latitude(),
                track.position().longitude(),
                track.rangeMeters(),
                track.errorRadiusMeters(),
                track.updatedAt(),
                trail);
    }
}
