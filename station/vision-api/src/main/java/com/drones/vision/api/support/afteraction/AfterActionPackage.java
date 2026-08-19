package com.drones.vision.api.support.afteraction;

import com.drones.vision.events.application.UsageRecording;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.platform.AuditEntry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the after-action evidence package needs — both the manifest's own fields (docs/plans/
 * active/AFTER-ACTION-PLAN.md &sect;3.1) and the raw domain content the archive's eight entries are
 * built from (&sect;3.2). {@link AfterActionAssembler#assemble} produces this; DTO/JSON mapping is
 * the controller's job (D1), not this record's — it names no Jackson/Spring type.
 *
 * <p>{@code complete}/{@code caveats} are not stored fields: both are pure functions of {@link
 * #parts()}, so there is exactly one place a part's state can drift from what the manifest reports
 * — this record's own list.
 *
 * @param assetId       the asset this package describes
 * @param assetName     the asset's display name, for the manifest and {@code README.txt}
 * @param usageId       the usage (flight) this package describes
 * @param startedAt     when the flight started ({@code AssetUsage#startedAt()})
 * @param endedAt       when the flight ended, or {@code null} for a still-open usage
 * @param open          {@code true} iff {@code endedAt} is {@code null}
 * @param generatedAt   when this package was assembled
 * @param scopedTo      who this package was assembled for (D6) — the controller's resolved label
 *                      for the requesting viewer, not re-derived here
 * @param parts         all six part rows, in the frozen order; exactly {@link
 *                      AfterActionPartKind#values()}{@code .length} entries
 * @param telemetry     the (possibly thinned) telemetry series backing {@code telemetry.csv}
 * @param detections    the flattened detection rows backing {@code detections.csv}
 * @param marks         marks created inside the flight's window and visible to the viewer (D5),
 *                      backing {@code marks.geojson}
 * @param passport      this usage's captured PREFLIGHT/POSTFLIGHT snapshots, whichever exist —
 *                      never {@code null} itself, only its own two fields are
 * @param recording     the resolved recording/clip-export reference, or {@link Optional#empty()}
 * @param auditEntries  this asset's audit trail, or empty when the viewer's role may not read it
 *                      (see the {@code audit} part's own {@code state})
 */
public record AfterActionPackage(AssetId assetId, String assetName, UsageId usageId, Instant startedAt,
                                  Instant endedAt, boolean open, Instant generatedAt, String scopedTo,
                                  List<AfterActionPart> parts, List<Telemetry> telemetry,
                                  List<DetectionRow> detections, List<Mark> marks, FlightPassport passport,
                                  Optional<UsageRecording> recording, List<AuditEntry> auditEntries) {

    public AfterActionPackage {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(assetName, "assetName must not be null");
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(scopedTo, "scopedTo must not be null");
        Objects.requireNonNull(parts, "parts must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        Objects.requireNonNull(detections, "detections must not be null");
        Objects.requireNonNull(marks, "marks must not be null");
        Objects.requireNonNull(passport, "passport must not be null");
        Objects.requireNonNull(recording, "recording must not be null");
        Objects.requireNonNull(auditEntries, "auditEntries must not be null");
        if (parts.size() != AfterActionPartKind.values().length) {
            throw new IllegalArgumentException(
                    "AfterActionPackage parts must carry exactly " + AfterActionPartKind.values().length
                            + " rows, one per AfterActionPartKind: " + parts.size());
        }
        parts = List.copyOf(parts);
        telemetry = List.copyOf(telemetry);
        detections = List.copyOf(detections);
        marks = List.copyOf(marks);
        auditEntries = List.copyOf(auditEntries);
    }

    /**
     * Whether every part resolved to {@link AfterActionPartState#PRESENT} (docs/plans/active/
     * AFTER-ACTION-PLAN.md &sect;3.1's {@code complete} rule).
     *
     * @return {@code true} iff no part is {@code ABSENT}, {@code TRUNCATED} or {@code FORBIDDEN}
     */
    public boolean complete() {
        return parts.stream().allMatch(p -> p.state() == AfterActionPartState.PRESENT);
    }

    /**
     * Every non-{@code null} note, verbatim, in part order, regardless of {@code state}
     * (docs/plans/active/AFTER-ACTION-PLAN.md &sect;3.1's {@code caveats} rule, corrected — the
     * plan's own worked example originally suppressed a {@code PRESENT} part's note, which would
     * have hidden the marks part's standing "not bound to a flight" qualifier, D5, from every
     * package that has any marks at all). A caveat and its {@code parts[].note} are the same
     * sentence appearing twice on purpose, so a reader can match them byte-for-byte.
     *
     * <p>Not tied to {@link #complete()}: an approximation is not an absence, so {@code complete}
     * can be {@code true} while {@code caveats} is non-empty (e.g. every part {@code PRESENT} but
     * marks carries its standing note).
     *
     * @return the ordered caveat list
     */
    public List<String> caveats() {
        return parts.stream()
                .map(AfterActionPart::note)
                .filter(Objects::nonNull)
                .toList();
    }
}
