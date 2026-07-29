package com.drones.vision.api;

import com.drones.vision.api.dto.UsageRecordingResponse;
import com.drones.vision.api.dto.UsageTimelineResponse;
import com.drones.vision.application.ReplayService;
import com.drones.vision.application.UsageTimeline;
import com.drones.vision.domain.model.UsageId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;

/**
 * Driving REST adapter for flight replay (docs/MVP2-PLAN.md §R, R-a): a downsampled,
 * time-windowed view over one {@code AssetUsage}'s telemetry/detection history, plus (docs/
 * OPS-CORE-PLAN.md §R, R-b) that same usage's recorded-clip URL, if one is available — both
 * usage-scoped reads share the single {@link ReplayService} collaborator, so the second endpoint
 * joins this controller rather than standing up a new one for one more method (see {@code
 * .claude/skills/java-clean-code/SKILL.md}: "can an existing service/controller own this method
 * instead of a new type?").
 *
 * <p>Kept as its own controller rather than folded into {@link AssetController} (which already
 * hosts the older {@code GET /api/usages/{usageId}/telemetry}) — no standalone "usages controller"
 * existed yet to fold into, and this endpoint has a single, focused dependency ({@link
 * ReplayService}) unrelated to {@link AssetController}'s asset-management concerns. See that
 * controller's Gotchas for the resulting (harmless but slightly inconsistent) split of
 * usage-scoped endpoints across two classes.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class UsageTimelineController {

    /** Default {@code maxPoints} when the query parameter is absent. */
    static final int DEFAULT_MAX_POINTS = 500;

    private final ReplayService replayService;

    public UsageTimelineController(ReplayService replayService) {
        this.replayService = Objects.requireNonNull(replayService, "replayService must not be null");
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
     * @param maxPoints maximum points per series after downsampling; defaults to {@value
     *                  #DEFAULT_MAX_POINTS}, silently clamped to an internal ceiling if larger
     * @return the merged, time-ordered replay window
     */
    @GetMapping("/api/usages/{usageId}/timeline")
    public UsageTimelineResponse timeline(@PathVariable String usageId,
                                           @RequestParam(required = false) Long fromMs,
                                           @RequestParam(required = false) Long toMs,
                                           @RequestParam(defaultValue = "" + DEFAULT_MAX_POINTS) int maxPoints) {
        Instant from = fromMs == null ? null : Instant.ofEpochMilli(fromMs);
        Instant to = toMs == null ? null : Instant.ofEpochMilli(toMs);
        UsageTimeline timeline = replayService.timeline(UsageId.of(usageId), from, to, maxPoints);
        return UsageTimelineResponse.from(timeline);
    }

    /**
     * Serves a usage's recording/clip-export URL, if one is available (docs/OPS-CORE-PLAN.md §R).
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
