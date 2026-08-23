package com.drones.vision.warehouse.application.usage;

import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;

import java.util.List;
import java.util.Optional;

/**
 * Lists finished (and still-open) {@link com.drones.vision.warehouse.domain.model.AssetUsage}s as
 * display-ready rows — the read side behind {@code GET /api/usages}, the "replay library"
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, docs/extracts/design/10-replay.md). {@link
 * com.drones.vision.events.application.ReplayService} (sibling package) is the detail view over
 * <em>one</em> usage's telemetry/detection timeline; this
 * is the cross-fleet list a caller picks a usage from before opening that detail view — a genuinely
 * different collaborator set ({@link AssetRepositoryPort} to resolve a display name and enforce
 * visibility, neither of which {@code ReplayService} needs), hence its own service area rather
 * than a method bolted onto {@code ReplayService}.
 */
public interface UsageService {

    /**
     * Lists the most recent usages the caller may see, newest first by {@code startedAt}.
     *
     * @param scope       what the caller may see; a non-{@link VisibilityScope#isUnbounded()} scope
     *                    silently excludes any usage whose owning asset is outside it (or no
     *                    longer resolvable — see {@code DefaultUsageService})
     * @param assetIdOrNull restricts the list to one asset's usages, or {@code null} for fleet-wide
     * @param limit       maximum number of usages to return; must be positive, silently clamped to
     *                    an internal ceiling if larger
     * @return an immutable, newest-first snapshot of the usages the caller may see
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    List<UsageSummary> recent(VisibilityScope scope, AssetId assetIdOrNull, int limit);

    /**
     * Finds the usage one stream opened — the same row {@link #recent} would list, reached by the
     * stream id a caller is already holding (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.6).
     *
     * <p>This is the read side of "a stopped stream is not a state, it is a record": a stream that
     * has ended is gone from {@code GET /api/streams}, and this is how a caller finds out what it
     * was rather than only that it is absent.
     *
     * <p>Out of scope and does-not-exist deliberately return the <b>same</b> {@link
     * Optional#empty()} — the same "hide what the caller may not see, never reveal it by the shape
     * of the answer" posture {@link #recent} already takes by silently excluding rows.
     *
     * @param scope    what the caller may see
     * @param streamId the stream whose usage to find
     * @return the usage that stream opened, or {@link Optional#empty()} if none exists or the
     *         caller may not see it
     */
    Optional<UsageSummary> byStream(VisibilityScope scope, StreamId streamId);
}
