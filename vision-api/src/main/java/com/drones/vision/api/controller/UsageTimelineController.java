package com.drones.vision.api.controller;

import com.drones.vision.api.dto.UsageRecordingResponse;
import com.drones.vision.api.dto.UsageSummaryResponse;
import com.drones.vision.api.dto.UsageTimelineResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.replay.DefaultReplayService;
import com.drones.vision.application.replay.ReplayService;
import com.drones.vision.application.replay.UsageTimeline;
import com.drones.vision.application.usage.DefaultUsageService;
import com.drones.vision.application.usage.UsageService;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.UsageId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for {@code /api/usages}: the fleet-wide "replay library" list ({@link
 * #recent}, docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, docs/extracts/design/10-replay.md), flight replay
 * (docs/plans/done/MVP2-PLAN.md §R, R-a) — a downsampled, time-windowed view over one {@code AssetUsage}'s
 * telemetry/detection history — plus (docs/plans/done/OPS-CORE-PLAN.md §R, R-b) that same usage's
 * recorded-clip URL, if one is available. All three are usage-scoped reads that belong together on
 * one controller rather than standing up a new class per endpoint (see {@code
 * .claude/skills/java-clean-code/SKILL.md}: "can an existing service/controller own this method
 * instead of a new type?") — {@link #recent} and {@link #timeline}/{@link #recording} do lean on
 * different application-layer collaborators ({@link UsageService} vs. {@link ReplayService}, see
 * {@link UsageService}'s own javadoc for why they're separate services), so this controller simply
 * holds both rather than forcing one to depend on the other.
 *
 * <p>Kept as its own controller rather than folded into {@link AssetController} (which already
 * hosts the older {@code GET /api/usages/{usageId}/telemetry}) — no standalone "usages controller"
 * existed yet to fold into, and this class's dependencies are unrelated to {@link
 * AssetController}'s asset-management concerns. See that controller's Gotchas for the resulting
 * (harmless but slightly inconsistent) split of usage-scoped endpoints across two classes.
 *
 * <h2>Visibility scoping (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1)</h2>
 * {@link #recent} is scoped to {@link CurrentUser#scope()}, exactly like {@link
 * AssetController#list}: a usage whose owning asset the caller may not see is silently excluded,
 * never revealed. {@link #timeline}/{@link #recording} are unchanged by this task and remain
 * unscoped — a pre-existing gap (any authenticated caller who already knows/guesses a usage id may
 * replay it), out of this task's scope to close.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class UsageTimelineController {

    private final ReplayService replayService;
    private final UsageService usageService;
    private final CurrentUser currentUser;

    public UsageTimelineController(ReplayService replayService, UsageService usageService, CurrentUser currentUser) {
        this.replayService = Objects.requireNonNull(replayService, "replayService must not be null");
        this.usageService = Objects.requireNonNull(usageService, "usageService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists the caller's most recent usages, newest first — the "replay library" a caller browses
     * before opening one in {@link #timeline}/the replay player.
     *
     * <p>{@code assetId}, if given, restricts the list to one asset's usages; a malformed UUID
     * surfaces as {@link IllegalArgumentException} (→400) via {@link ApiExceptionHandler}, the same
     * way every other malformed-id path parameter in this codebase does — an unknown or
     * out-of-scope {@code assetId} is not an error, it simply yields an empty list, same as {@link
     * AssetController#list} silently excluding what the caller may not see.
     *
     * @param limit   maximum number of usages to return; defaults to {@link
     *                DefaultUsageService#DEFAULT_LIMIT}, silently clamped to an internal ceiling if
     *                larger
     * @param assetId restricts the list to one asset, as a canonical UUID string, or absent for
     *                fleet-wide
     * @return the caller's visible usages, newest first
     */
    @GetMapping("/api/usages")
    public List<UsageSummaryResponse> recent(@RequestParam(required = false) Integer limit,
                                              @RequestParam(required = false) String assetId) {
        int effectiveLimit = limit != null ? limit : DefaultUsageService.DEFAULT_LIMIT;
        AssetId assetIdOrNull = assetId == null ? null : AssetId.of(assetId);
        return usageService.recent(currentUser.scope(), assetIdOrNull, effectiveLimit).stream()
                .map(UsageSummaryResponse::from)
                .toList();
    }

    /**
     * Serves a usage's replay timeline.
     *
     * <p>{@code fromMs}/{@code toMs} are epoch milliseconds; either or both absent default to the
     * usage's own {@code startedAt}/{@code endedAt} (or "now" for {@code toMs} on a still-open
     * usage) — see {@link ReplayService#timeline}. An unknown usage id surfaces as {@link
     * java.util.NoSuchElementException} (→404); a malformed UUID, a non-positive {@code
     * maxPoints}, or {@code toMs} before {@code fromMs} surface as {@link IllegalArgumentException}
     * (→400) — both via {@link ApiExceptionHandler}.
     *
     * @param usageId   the usage id, as a canonical UUID string
     * @param fromMs    inclusive lower window bound, epoch milliseconds, or absent for the usage's
     *                  own start
     * @param toMs      inclusive upper window bound, epoch milliseconds, or absent for the usage's
     *                  own end (or "now" if still open)
     * @param maxPoints maximum points per series after downsampling; defaults to {@link
     *                  DefaultReplayService#DEFAULT_MAX_POINTS}, silently clamped to an internal
     *                  ceiling if larger
     * @return the merged, time-ordered replay window
     */
    @GetMapping("/api/usages/{usageId}/timeline")
    public UsageTimelineResponse timeline(@PathVariable String usageId,
                                           @RequestParam(required = false) Long fromMs,
                                           @RequestParam(required = false) Long toMs,
                                           @RequestParam(required = false) Integer maxPoints) {
        Instant from = fromMs == null ? null : Instant.ofEpochMilli(fromMs);
        Instant to = toMs == null ? null : Instant.ofEpochMilli(toMs);
        int effectiveMaxPoints = maxPoints != null ? maxPoints : DefaultReplayService.DEFAULT_MAX_POINTS;
        UsageTimeline timeline = replayService.timeline(UsageId.of(usageId), from, to, effectiveMaxPoints);
        return UsageTimelineResponse.from(timeline);
    }

    /**
     * Serves a usage's recording/clip-export URL, if one is available (docs/plans/done/OPS-CORE-PLAN.md §R).
     *
     * <p>An unknown usage id surfaces as {@link java.util.NoSuchElementException} (→404); a
     * malformed UUID surfaces as {@link IllegalArgumentException} (→400) — both via {@link
     * ApiExceptionHandler}, same idiom as {@link #timeline}. A known usage with nothing to play
     * back (no video stream ever attached, or the configured stream publisher has no
     * recording/playback endpoint) is <b>not</b> an error — it still returns {@code 200
     * {"available":false}}, per the plan's "honest-cheap availability check" design.
     *
     * @param usageId the usage id, as a canonical UUID string
     * @return the resolved recording, or {@code {"available":false}} when none exists
     */
    @GetMapping("/api/usages/{usageId}/recording")
    public UsageRecordingResponse recording(@PathVariable String usageId) {
        return UsageRecordingResponse.from(replayService.recordingFor(UsageId.of(usageId)));
    }
}
