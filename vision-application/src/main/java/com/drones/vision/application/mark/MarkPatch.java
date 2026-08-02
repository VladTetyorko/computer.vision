package com.drones.vision.application.mark;

import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkStatus;

import java.util.Optional;
import com.drones.vision.application.asset.AssetEdit;
import com.drones.vision.application.device.DeviceEdit;

/**
 * A partial edit to a {@link com.drones.vision.domain.model.Mark} — annotation (label / note / kind
 * / position, including drag-to-correct) plus an optional lifecycle transition
 * (docs/TACTICAL-MARKS-PLAN.md §2, the frozen {@code PATCH /api/marks/{id}} contract).
 *
 * <p>Unlike {@link AssetEdit}/{@link DeviceEdit} (which use a bare nullable field to mean "leave
 * unchanged" — a field that can never itself legitimately be {@code null}), every component here is
 * an {@link Optional}: {@link Optional#empty()} means "leave this field unchanged",
 * {@code Optional.of(value)} means "set it to {@code value}". {@link #note()}'s own blank-normalizes-
 * to-{@code null} rule still applies once a present value reaches {@link
 * com.drones.vision.domain.model.Mark#withDetails}, so a present-but-blank note clears it.
 *
 * <p>{@link #status()} present and {@link MarkStatus#CLEARED} (or present and {@link
 * MarkStatus#ACTIVE}, re-opening) is gated to the mark's creator or a manager by {@link
 * DefaultMarkService#update} — every other field stays open to any in-scope viewer
 * (docs/TACTICAL-MARKS-PLAN.md, design decision F).
 *
 * @param kind     replacement kind, or {@link Optional#empty()} to keep the current one
 * @param label    replacement label, or {@link Optional#empty()} to keep the current one
 * @param note     replacement note, or {@link Optional#empty()} to keep the current one
 * @param position replacement position (drag-to-correct), or {@link Optional#empty()} to keep the
 *                 current one
 * @param status   replacement lifecycle status, or {@link Optional#empty()} to leave it unchanged
 */
public record MarkPatch(Optional<MarkKind> kind, Optional<String> label, Optional<String> note,
                         Optional<GeoPosition> position, Optional<MarkStatus> status) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final MarkPatch NOTHING =
            new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public MarkPatch {
        kind = kind == null ? Optional.empty() : kind;
        label = label == null ? Optional.empty() : label;
        note = note == null ? Optional.empty() : note;
        position = position == null ? Optional.empty() : position;
        status = status == null ? Optional.empty() : status;
    }
}
