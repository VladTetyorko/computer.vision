package com.drones.vision.api.support.afteraction;

import com.drones.vision.events.application.ReplayService;
import com.drones.vision.events.application.UsageRecording;
import com.drones.vision.events.application.UsageTimeline;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.AssetUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles one flight's after-action evidence package (docs/plans/done/AFTER-ACTION-PLAN.md,
 * the frozen wire contract &sect;3) out of five contexts' existing read surfaces — {@code
 * vision-warehouse} (the asset/usage), {@code vision-events} (telemetry/detections/recording),
 * {@code vision-map} (marks), {@code vision-flight} (the flight passport) and {@code
 * vision-platform} (the audit trail) — with no new context dependency (&sect;5 hazard 6: {@code
 * vision-events} must not gain one, and does not; this class lives in {@code vision-api}, which
 * already sees every context, the same precedent {@code RemediationOrchestrator} sets, D1).
 *
 * <h2>Framework-free by design</h2>
 * No Spring annotation, no Jackson import, constructor-injected collaborators only — so this
 * class's own tests are plain JUnit against hand-written fakes of {@link AssetService}/{@link
 * ReplayService}/{@link AfterActionSources}'s three members, not a {@code @SpringBootTest}. DTO/JSON
 * mapping is the controller's job (D1) — {@link #assemble} returns a domain-ish {@link
 * AfterActionPackage}, never a wire type.
 *
 * <h2>Six parts, always, in the frozen order</h2>
 * {@link #assemble} resolves {@link AfterActionPartKind#values()} in declaration order —
 * {@code telemetry, detections, marks, recording, passport, audit} — via one package-private
 * {@code resolve*} method per part, each independently unit-testable without going through the
 * whole pipeline. A part is never omitted; {@link AfterActionPartState} carries the truth (D3).
 *
 * <h2>Two authority gates, not one</h2>
 * <ul>
 *   <li><b>Top-level export authority</b> — {@link VisibilityScope#canManage(com.drones.vision.kernel.Ownership)}
 *       against the asset's own ownership, checked once, before any part is resolved. This is the
 *       same predicate {@code AssetController#requireManageable}/{@code
 *       VehicleProfileService#probe} already use for "seeing it is not the same as administering
 *       it" (D8's own reasoning, applied here to exporting evidence) — a PILOT sees their assigned
 *       aircraft (no 404) but may not export its evidence package (403, docs/plans/active/
 *       AFTER-ACTION-PLAN.md &sect;3.3's fourth row). Thrown as {@link AccessDeniedException}
 *       before any part resolution begins, so a caller who fails this gate never triggers a single
 *       downstream read.</li>
 *   <li><b>The {@code audit} part's own gate</b> — {@link VisibilityScope#canManageOrg()}, the
 *       exact condition {@code AuditController} already applies to {@code GET /api/audit}, applied
 *       here to one asset's slice of the trail. Produces {@link AfterActionPartState#FORBIDDEN}
 *       rather than failing the whole request (D3) — auditing may legitimately refuse without the
 *       rest of the package being unavailable.</li>
 * </ul>
 * <b>A structural note, not a bug</b>: under today's three-kind {@link VisibilityScope}, {@code
 * canManage(ownership) == true} always implies {@code canManageOrg() == true} (both reduce to
 * {@code UNBOUNDED} or a group-matching {@code GROUPS}) — so no real caller who clears the
 * top-level gate can ever see the {@code audit} part {@code FORBIDDEN}; only a caller who fails the
 * top-level gate could, and they never reach part resolution at all. The {@code FORBIDDEN} branch
 * is real, correctly wired to {@code AuditController}'s own policy, and exercised directly by
 * {@code resolveAudit}'s own unit tests — but is not reachable end-to-end via {@link #assemble}
 * with any of this codebase's three real roles today. Flagged rather than silently designed around
 * (see this wave's report).
 */
public final class AfterActionAssembler {

    private final AssetService assetService;
    private final ReplayService replayService;
    private final AfterActionSources sources;
    private final AfterActionProperties properties;

    public AfterActionAssembler(AssetService assetService, ReplayService replayService, AfterActionSources sources,
                                 AfterActionProperties properties) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.replayService = Objects.requireNonNull(replayService, "replayService must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Assembles the package for one usage of one asset, as seen by {@code scope}/{@code viewer}
     * (D6 — both describe the same requesting principal, through the two seams this codebase
     * already splits identity into: {@code VisibilityScope} for warehouse/flight/audit reads,
     * {@code MapAccessPolicy.Viewer} for map reads).
     *
     * @param assetId  the asset the usage belongs to
     * @param usageId  the usage (flight) to describe
     * @param scope    the requesting viewer's visibility scope
     * @param viewer   the requesting viewer, as the map's authorization model sees it
     * @param scopedTo a display label for the requesting viewer (D6's {@code scopedTo}); resolved
     *                 by the caller, not re-derived here
     * @return the assembled package
     * @throws NoSuchElementException if {@code assetId} is unknown or not visible to {@code scope}
     *                                (404), or {@code usageId} is unknown or does not belong to
     *                                {@code assetId} (404, same shape — never leaks that it exists
     *                                elsewhere)
     * @throws AccessDeniedException  if {@code scope} may see the asset but may not export its
     *                                evidence package (403)
     */
    public AfterActionPackage assemble(AssetId assetId, UsageId usageId, VisibilityScope scope, Viewer viewer,
                                        String scopedTo) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(scopedTo, "scopedTo must not be null");

        AssetDetails assetDetails = assetService.details(scope, assetId);
        Asset asset = assetDetails.summary().asset();
        if (!scope.canManage(asset.ownership())) {
            throw new AccessDeniedException("Not permitted to export the after-action package for asset " + assetId.value());
        }

        UsageTimeline timeline = replayService.timeline(usageId, null, null, properties.maxPoints());
        AssetUsage usage = timeline.usage();
        if (!usage.assetId().equals(assetId)) {
            throw new NoSuchElementException("No usage " + usageId.value());
        }

        Instant startedAt = usage.startedAt();
        Instant endedAt = usage.endedAt();
        boolean open = endedAt == null;
        Instant windowEnd = endedAt != null ? endedAt : Instant.now();

        List<Telemetry> telemetry = timeline.telemetry();
        List<DetectionRow> detections = flattenDetections(timeline.detections());
        List<Mark> marks = filterMarksInWindow(sources.markService().list(viewer), startedAt, windowEnd);
        Optional<UsageRecording> recording = replayService.recordingFor(usageId);
        FlightPassport passport = sources.vehicleProfileService().passport(assetId, usageId, scope);
        AuditResolution audit = resolveAudit(assetId, scope);

        List<AfterActionPart> parts = new ArrayList<>(AfterActionPartKind.values().length);
        parts.add(resolveTelemetry(telemetry));
        parts.add(resolveDetections(detections));
        parts.add(resolveMarks(marks));
        parts.add(resolveRecording(recording));
        parts.add(resolvePassport(passport));
        parts.add(audit.part());

        return new AfterActionPackage(assetId, asset.displayName(), usageId, startedAt, endedAt, open, Instant.now(),
                scopedTo, parts, telemetry, detections, marks, passport, recording, audit.entries());
    }

    /**
     * D7: a returned count equal to the requested ceiling means {@code DefaultReplayService#thin}
     * actually thinned the series (it returns the list unchanged whenever {@code size <=
     * maxPoints}), so the source series is larger than what this package carries.
     */
    AfterActionPart resolveTelemetry(List<Telemetry> telemetry) {
        if (telemetry.isEmpty()) {
            return new AfterActionPart(AfterActionPartKind.TELEMETRY, AfterActionPartState.ABSENT, 0,
                    "no telemetry was recorded for this flight");
        }
        if (telemetry.size() == properties.maxPoints()) {
            return new AfterActionPart(AfterActionPartKind.TELEMETRY, AfterActionPartState.TRUNCATED, telemetry.size(),
                    "thinned to the " + properties.maxPoints()
                            + "-point ceiling; the source series is larger");
        }
        return new AfterActionPart(AfterActionPartKind.TELEMETRY, AfterActionPartState.PRESENT, telemetry.size(), null);
    }

    /**
     * Thins exactly as telemetry does — {@code DefaultReplayService#timeline} passes both series
     * through the same {@code thin(…, effectiveMaxPoints)} call — so the same detection applies.
     * D7 named only telemetry; a package that reported a thinned detection series as {@code PRESENT}
     * would be silently omitting evidence, which is the one thing this whole part-state machinery
     * exists to prevent.
     */
    AfterActionPart resolveDetections(List<DetectionRow> detections) {
        if (detections.isEmpty()) {
            return new AfterActionPart(AfterActionPartKind.DETECTIONS, AfterActionPartState.ABSENT, 0,
                    "no detections were recorded for this flight");
        }
        if (detections.size() == properties.maxPoints()) {
            return new AfterActionPart(AfterActionPartKind.DETECTIONS, AfterActionPartState.TRUNCATED, detections.size(),
                    "thinned to the " + properties.maxPoints() + "-point ceiling; the source series is larger");
        }
        return new AfterActionPart(AfterActionPartKind.DETECTIONS, AfterActionPartState.PRESENT, detections.size(), null);
    }

    /**
     * D5: a mark carries no {@code assetId}/{@code usageId} at all — "marks from this flight" can
     * only ever mean marks created inside the flight's time window and visible to the viewer, an
     * approximation that must say so out loud whenever it actually returns anything, not just when
     * the state is otherwise unqualified.
     */
    AfterActionPart resolveMarks(List<Mark> marks) {
        if (marks.isEmpty()) {
            return new AfterActionPart(AfterActionPartKind.MARKS, AfterActionPartState.ABSENT, 0,
                    "no marks were created inside this flight's time window");
        }
        return new AfterActionPart(AfterActionPartKind.MARKS, AfterActionPartState.PRESENT, marks.size(),
                "marks created inside the flight window and visible to you; a mark is not bound to a flight");
    }

    AfterActionPart resolveRecording(Optional<UsageRecording> recording) {
        if (recording.isEmpty()) {
            return new AfterActionPart(AfterActionPartKind.RECORDING, AfterActionPartState.ABSENT, 0,
                    "no recording is configured for this stream");
        }
        return new AfterActionPart(AfterActionPartKind.RECORDING, AfterActionPartState.PRESENT, 1, null);
    }

    AfterActionPart resolvePassport(FlightPassport passport) {
        int count = (passport.preflightProfile() != null ? 1 : 0) + (passport.postflightProfile() != null ? 1 : 0);
        if (count == 0) {
            return new AfterActionPart(AfterActionPartKind.PASSPORT, AfterActionPartState.ABSENT, 0,
                    "no PREFLIGHT or POSTFLIGHT vehicle-profile snapshot was captured for this flight");
        }
        return new AfterActionPart(AfterActionPartKind.PASSPORT, AfterActionPartState.PRESENT, count, null);
    }

    /**
     * Mirrors {@code AuditController}'s own {@code !scope.canManageOrg()} gate exactly — the
     * "underlying service" for audit visibility is that one-line policy, not a dedicated
     * application service (none exists to delegate to), so this reapplies the identical condition
     * rather than inventing a new one.
     */
    AuditResolution resolveAudit(AssetId assetId, VisibilityScope scope) {
        if (!scope.canManageOrg()) {
            AfterActionPart part = new AfterActionPart(AfterActionPartKind.AUDIT, AfterActionPartState.FORBIDDEN, 0,
                    "your role cannot read the audit trail");
            return new AuditResolution(part, List.of());
        }
        List<AuditEntry> entries = sources.auditTrailPort()
                .findByTarget(AuditTargetType.ASSET, assetId.value().toString(), properties.auditLimit());
        if (entries.isEmpty()) {
            AfterActionPart part = new AfterActionPart(AfterActionPartKind.AUDIT, AfterActionPartState.ABSENT, 0,
                    "no audit entries exist for this asset");
            return new AuditResolution(part, entries);
        }
        AfterActionPart part = new AfterActionPart(AfterActionPartKind.AUDIT, AfterActionPartState.PRESENT,
                entries.size(), null);
        return new AuditResolution(part, entries);
    }

    /** One row per detected object, not one row per frame (see {@link DetectionRow}'s own javadoc). */
    static List<DetectionRow> flattenDetections(List<DetectionResult> results) {
        List<DetectionRow> rows = new ArrayList<>();
        for (DetectionResult result : results) {
            for (Detection detection : result.detections()) {
                Long trackId = detection.track() == null ? null : detection.track().trackId();
                rows.add(new DetectionRow(result.capturedAt(), detection.label(), detection.confidence(),
                        detection.box().x(), detection.box().y(), detection.box().width(), detection.box().height(),
                        trackId));
            }
        }
        return List.copyOf(rows);
    }

    /** Inclusive both ends, matching {@code DefaultReplayService}'s own telemetry/detection windowing. */
    static List<Mark> filterMarksInWindow(List<Mark> marks, Instant startedAt, Instant windowEnd) {
        return marks.stream()
                .filter(m -> !m.createdAt().isBefore(startedAt) && !m.createdAt().isAfter(windowEnd))
                .toList();
    }

    /** {@link #resolveAudit}'s own result: the manifest row, plus the raw entries {@code audit.csv} needs. */
    record AuditResolution(AfterActionPart part, List<AuditEntry> entries) {
    }
}
