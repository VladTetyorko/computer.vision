package com.drones.vision.application.mark;

import com.drones.vision.domain.model.Affiliation;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkStatus;

import java.util.Optional;

/**
 * A partial edit to a {@link com.drones.vision.domain.model.Mark} — annotation (label / note / kind
 * / affiliation / position, including drag-to-correct) plus an optional lifecycle transition
 * (docs/plans/done/MAP-REWORK-PLAN.md §3/§4.2, the frozen {@code PATCH /api/map/marks/{id}} contract,
 * superseding docs/plans/done/TACTICAL-MARKS-PLAN.md §2's shape — {@link #affiliation()} is the one new field).
 *
 * <p>Unlike {@link com.drones.vision.application.asset.AssetEdit}/{@link com.drones.vision.application.device.DeviceEdit} (which use a bare nullable field to mean "leave
 * unchanged" — a field that can never itself legitimately be {@code null}), every component here is
 * an {@link Optional}: {@link Optional#empty()} means "leave this field unchanged",
 * {@code Optional.of(value)} means "set it to {@code value}". {@link #note()}'s own blank-normalizes-
 * to-{@code null} rule still applies once a present value reaches {@link
 * com.drones.vision.domain.model.Mark#withDetails}, so a present-but-blank note clears it.
 *
 * <p>Every field here is gated identically by {@link DefaultMarkService#patch}: the mark's creator
 * while it is still {@code UNVERIFIED}, or a viewer with {@code MapAccessPolicy#canManage} on its
 * layer — including a {@link #status()} change (docs/plans/done/MAP-REWORK-PLAN.md §3).
 *
 * @param kind        replacement kind, or {@link Optional#empty()} to keep the current one
 * @param affiliation replacement affiliation, or {@link Optional#empty()} to keep the current one
 * @param label       replacement label, or {@link Optional#empty()} to keep the current one
 * @param note        replacement note, or {@link Optional#empty()} to keep the current one
 * @param position    replacement position (drag-to-correct), or {@link Optional#empty()} to keep the
 *                    current one
 * @param status      replacement lifecycle status, or {@link Optional#empty()} to leave it unchanged
 */
public record MarkPatch(Optional<MarkKind> kind, Optional<Affiliation> affiliation, Optional<String> label,
                         Optional<String> note, Optional<GeoPosition> position, Optional<MarkStatus> status) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final MarkPatch NOTHING = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    public MarkPatch {
        kind = kind == null ? Optional.empty() : kind;
        affiliation = affiliation == null ? Optional.empty() : affiliation;
        label = label == null ? Optional.empty() : label;
        note = note == null ? Optional.empty() : note;
        position = position == null ? Optional.empty() : position;
        status = status == null ? Optional.empty() : status;
    }
}
