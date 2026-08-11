package com.drones.vision.application.map;

import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.GeoPosition;

import java.util.List;
import java.util.Optional;

/**
 * A partial edit to a {@link Drawing} — geometry (drag a vertex) and/or descriptive fields
 * (docs/plans/done/MAP-REWORK-PLAN.md §4.2's {@code PatchDrawingRequest}).
 *
 * <p>Every component is an {@link Optional}, matching {@code MarkPatch}'s own idiom rather than
 * {@code AssetEdit}/{@code DeviceEdit}'s bare-nullable one: a present-but-blank {@link #label()} is
 * a meaningful "clear the label" distinct from an absent one ("leave it unchanged"), the same
 * ambiguity {@code MarkPatch}'s own javadoc reasons about for {@code note}.
 *
 * @param points     replacement geometry, or {@link Optional#empty()} to keep the current one
 * @param label      replacement label, or {@link Optional#empty()} to keep the current one
 * @param colorToken replacement colour token, or {@link Optional#empty()} to keep the current one
 */
public record DrawingPatch(Optional<List<GeoPosition>> points, Optional<String> label,
                            Optional<String> colorToken) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final DrawingPatch NOTHING =
            new DrawingPatch(Optional.empty(), Optional.empty(), Optional.empty());

    public DrawingPatch {
        points = points == null ? Optional.empty() : points.map(List::copyOf);
        label = label == null ? Optional.empty() : label;
        colorToken = colorToken == null ? Optional.empty() : colorToken;
    }
}
