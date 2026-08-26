package com.drones.vision.warehouse.application.usage;

import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.time.Instant;

/**
 * Owns the {@link AssetUsage} session lifecycle — the write side of
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3: "warehouse must be the only module
 * that constructs or persists an {@code AssetUsage}". vision-perception's {@code UsageTracker}
 * decides <em>when</em> a session starts/stops (watching streams and telemetry) and <em>what
 * phase</em> a sample implies (translating flight's {@code FlightPhaseRule} verdicts, since that
 * translation must not move into this pure-leaf module — see {@code UsagePhase}'s own javadoc);
 * this service is what it calls to actually open, update, and close the aggregate. A sibling of
 * {@link UsageService} (the fleet-wide read list) rather than an extension of it: the two have
 * different collaborator sets and different callers — {@link UsageService} is a scope-checked
 * read behind {@code GET /api/usages}, this is an unscoped write API for the one internal
 * component that drives session lifecycle, matching the precedent {@link UsageService}'s own
 * javadoc already sets against {@code ReplayService}.
 *
 * <h2>Pure fold, explicit save</h2>
 * {@link #fold} and {@link #updatePhase} never write to the repository — they return a new,
 * in-memory {@link AssetUsage} only. This is deliberate: {@code UsageTracker} coalesces its
 * summary writes onto {@code UsageSummaryBatchSettings}'s (vision-perception) size-or-time bound
 * (docs/plans/done/SCALE-100-PLAN.md S4), and that timing decision belongs to
 * the caller driving the session, not to this service. {@link #open} and {@link #close}, by
 * contrast, always persist immediately — a session boundary is not something any caller has ever
 * had reason to defer, and neither did before this wave. {@link #save} is the explicit write a
 * caller uses once it has decided a folded/phase-updated usage is ready to persist.
 *
 * <h2>{@link #usageBelongsToAsset} (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5)</h2>
 * Filed here rather than on {@link UsageService} even though it is a read, not a write: every
 * {@link UsageService} method takes a {@link com.drones.vision.platform.VisibilityScope}, and this
 * question deliberately does not — its one caller (vision-flight's {@code
 * DefaultVehicleProfileService#passport}/{@code #driftFromPreviousFlight}) has already scope-checked
 * the asset itself and only needs an uncapped membership check against a {@code usageId} it was
 * handed, not a second scoped read. Adding an unscoped method beside {@link UsageService}'s
 * all-scoped ones would be a footgun the next reader could copy without noticing the difference;
 * this interface's whole surface is already unscoped, so it is the honest home for one more.
 */
public interface UsageSessionService {

    /**
     * Opens a new usage for {@code assetId}, stamped with the given {@code streamId} (or {@code
     * null} for a telemetry-only session with no video stream — see {@link AssetUsage#streamId()}),
     * persists it, and returns the persisted usage. The opened usage always starts in {@link
     * UsagePhase#PREFLIGHT} — nothing "transitions into" a session's first phase, it is simply
     * what every session starts as.
     *
     * @param assetId   the asset this usage belongs to
     * @param streamIdOrNull the stream whose start opened this usage, or {@code null}
     * @param startedAt when the usage was opened
     * @return the persisted, newly opened usage
     */
    AssetUsage open(AssetId assetId, StreamId streamIdOrNull, Instant startedAt);

    /**
     * Folds one telemetry sample's position and the caller-computed phase into {@code usage},
     * returning a new {@link AssetUsage} — {@code startPosition} is set once, on the first sample
     * that carries a position; {@code lastPosition} tracks the most recent positioned sample;
     * {@code sampleCount} increments on every call, positioned or not. Does <b>not</b> persist —
     * see this interface's class javadoc.
     *
     * @param usage    the usage to fold into; must be currently open
     * @param position the sample's position, or {@code null} if this sample carries none
     * @param phase    the phase the usage should hold after this sample
     * @return a new {@code AssetUsage} with the fold applied; not yet persisted
     */
    AssetUsage fold(AssetUsage usage, GeoPosition position, UsagePhase phase);

    /**
     * Returns a copy of {@code usage} with a different phase, with no other field changed. Does
     * <b>not</b> persist — see this interface's class javadoc. Used for phase transitions that are
     * not driven by a fresh sample (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3's
     * silence-driven {@code LINK_LOST}/{@code ABANDONED} transitions).
     *
     * @param usage the usage to update
     * @param phase the replacement phase
     * @return a new {@code AssetUsage} with {@code phase} replaced; not yet persisted
     */
    AssetUsage updatePhase(AssetUsage usage, UsagePhase phase);

    /**
     * Closes {@code usage} at {@code endedAt} with the given final phase, persists it, and returns
     * the persisted usage — the terminal write of a session, always immediate (see this
     * interface's class javadoc).
     *
     * @param usage   the usage to close; must be currently open
     * @param phase   the final phase to record (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3:
     *                {@code IN_FLIGHT}/{@code LINK_LOST} close to {@code ABANDONED}, everything
     *                else to {@code CLOSED} — a decision {@code FlightPhaseRule} makes, handed in
     *                already computed)
     * @param endedAt when the usage was closed; must not be before the usage's {@code startedAt}
     * @return the persisted, closed usage
     */
    AssetUsage close(AssetUsage usage, UsagePhase phase, Instant endedAt);

    /**
     * Persists {@code usage} as-is — the explicit write {@link #fold}/{@link #updatePhase}
     * deliberately do not perform themselves. Upserts by {@link AssetUsage#id()}, per {@link
     * AssetUsageRepositoryPort#save}.
     *
     * @param usage the usage to persist
     * @return the persisted usage
     */
    AssetUsage save(AssetUsage usage);

    /**
     * Whether a usage with {@code usageId} exists and genuinely belongs to {@code assetId} — an
     * unscoped, uncapped membership check (see this interface's class javadoc for why it lives
     * here) for a caller that has already resolved and scope-gated the asset itself and only needs
     * to verify a usage id it was handed is not being used to reach another asset's data.
     *
     * @param usageId the usage to check
     * @param assetId the asset it must belong to
     * @return {@code true} if a usage with this id exists and its {@code assetId} equals the given one
     */
    boolean usageBelongsToAsset(UsageId usageId, AssetId assetId);
}
