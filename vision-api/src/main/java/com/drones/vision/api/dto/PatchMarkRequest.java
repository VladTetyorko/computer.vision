package com.drones.vision.api.dto;

import com.drones.vision.application.MarkPatch;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkStatus;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Request body for {@code PATCH /api/marks/{id}} (docs/TACTICAL-MARKS-PLAN.md §4's frozen wire
 * contract) — a true partial patch: every field is optional, and only a present field changes
 * anything, mirroring {@link UpdateStreamConfigRequest}'s "absent means unchanged" convention
 * rather than {@link com.drones.vision.api.dto.GeofenceZoneRequest}'s wholesale-replace shape.
 *
 * <p>A present-but-blank {@code note} clears it — {@code null}/absent leaves it unchanged, an
 * empty string {@code ""} is a present value that {@link com.drones.vision.domain.model.Mark
 * #withDetails} then normalizes to {@code null} — the same "blank distinguishes from absent only
 * via an explicit empty string" trick {@link MarkPatch}'s own javadoc documents.
 *
 * @param kind     replacement kind ({@code "TARGET"}/{@code "HAZARD"}/{@code "POI"}/{@code
 *                 "FRIENDLY"}, matched case-insensitively), or {@code null} to leave it unchanged
 * @param label    replacement label, or {@code null} to leave it unchanged
 * @param note     replacement note (blank clears it), or {@code null} to leave it unchanged
 * @param position replacement position (drag-to-correct), or {@code null} to leave it unchanged
 * @param status   replacement lifecycle status ({@code "ACTIVE"}/{@code "CLEARED"}, matched
 *                 case-insensitively), or {@code null} to leave it unchanged
 */
public record PatchMarkRequest(String kind, String label, String note, CreateMarkRequest.PointRequest position,
                                String status) {

    /**
     * Converts this request to the application-layer patch.
     *
     * @return the equivalent {@link MarkPatch}
     * @throws IllegalArgumentException if {@code kind} or {@code status} is present but unrecognized
     */
    public MarkPatch toPatch() {
        return new MarkPatch(
                kind == null ? Optional.empty() : Optional.of(CreateMarkRequest.toKind(kind)),
                Optional.ofNullable(label),
                Optional.ofNullable(note),
                position == null ? Optional.empty() : Optional.of(position.toPosition()),
                status == null ? Optional.empty() : Optional.of(toStatus(status)));
    }

    private static MarkStatus toStatus(String status) {
        for (MarkStatus candidate : MarkStatus.values()) {
            if (candidate.name().equalsIgnoreCase(status)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + status + " (valid values: "
                + Arrays.stream(MarkStatus.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}
