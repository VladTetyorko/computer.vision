package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.ParameterDrift;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;

import java.time.Duration;
import java.util.List;

/**
 * The PROBE stage (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 3.1) -- observe a vehicle and
 * remember what was observed. See {@link DefaultVehicleProfileService} for the one implementation
 * and its scope/audit gate.
 *
 * <p><b>O11, "the flight passport"</b> (section 2.4): {@link #captureSnapshot}/{@link #passport}/
 * {@link #driftFromPreviousFlight} extend this same service rather than a new interface -- they
 * probe and read the same aggregate ({@code VehicleProfile}) through the same collaborators as
 * {@link #probe}/{@link #latestProfile}, and per {@code .claude/skills/java-clean-code/SKILL.md}
 * SS5 ("can an existing service own this method instead of a new type? usually yes") a passport is
 * a different question about the same data, not a different service area.
 */
public interface VehicleProfileService {

    /**
     * Actively probes the device behind {@code assetId} and persists the resulting snapshot.
     *
     * @throws java.util.NoSuchElementException                if {@code assetId} is unknown (404)
     * @throws com.drones.vision.platform.AccessDeniedException if the actor may not manage this
     *                                                            asset (403, audited) -- probing puts
     *                                                            traffic on the aircraft's link
     * @throws IllegalStateException                             if the asset has no device this
     *                                                            platform can probe (409, not an
     *                                                            attempt, not audited)
     */
    VehicleProfile probe(AssetId assetId, Duration window, UserId actor, Authority scope);

    /**
     * The most recently observed profile for {@code assetId}, scoped like every other asset read.
     *
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown, out of scope, or has
     *                                          never been probed (404 in all three cases -- a scoped
     *                                          read never distinguishes "not yours" from "does not
     *                                          exist" from "nothing to show yet")
     */
    VehicleProfile latestProfile(AssetId assetId, VisibilityScope scope);

    /**
     * The pre-registration probe (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 3.1, stage 3):
     * observes a candidate keyed only by {@code linkKey}, before any {@code Asset}/{@code Device}
     * exists to scope against or persist a snapshot against (D7 -- registration is the last stage).
     *
     * @param linkKey the candidate's identity ({@code "udp://host:port#sysid"})
     * @param window  the probe budget
     * @param actor   who requested the probe -- carried for parity with every other entry point in
     *               this service even though, absent an asset, there is nothing yet to audit against
     * @return the observed snapshot, never persisted by this call
     */
    VehicleProfile probeCandidate(String linkKey, Duration window, UserId actor);

    /**
     * Captures a fresh snapshot and attaches it to {@code usageId} as its {@code phase} passport
     * entry (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 2.4/O11 -- "a VehicleProfile
     * snapshot taken at PREFLIGHT and again at POSTFLIGHT, attached to the AssetUsage"). Otherwise
     * identical to {@link #probe}: same scope gate, same device resolution, same audit shape, plus
     * the usage tag.
     *
     * @param phase must be {@link FlightPhase#PREFLIGHT} or {@link FlightPhase#POSTFLIGHT}; any
     *              other value is rejected before anything is resolved or audited -- a caller-shape
     *              error, not an authority decision
     * @throws IllegalArgumentException                        if {@code phase} is not PREFLIGHT or
     *                                                            POSTFLIGHT
     * @throws java.util.NoSuchElementException                if {@code assetId} is unknown (404)
     * @throws com.drones.vision.platform.AccessDeniedException if the actor may not manage this
     *                                                            asset (403, audited)
     * @throws IllegalStateException                             if the asset has no device this
     *                                                            platform can probe (409, not an
     *                                                            attempt, not audited)
     */
    VehicleProfile captureSnapshot(AssetId assetId, UsageId usageId, FlightPhase phase, Duration window,
                                    UserId actor, Authority scope);

    /**
     * The passport for one usage: its PREFLIGHT and POSTFLIGHT snapshots, whichever have been
     * captured so far. A scoped read, same 404 collapse as {@link #latestProfile}, plus one more
     * case: {@code usageId} not belonging to this asset also 404s, since a passport keyed purely by
     * {@code usageId} would otherwise leak another asset's profile data to a caller who only has
     * scope over this one. This membership check is <b>not</b> limited to the asset's recent
     * usages -- a passport must resolve a flight of any age, so it resolves {@code usageId}
     * directly rather than through a capped recent-usage list (see {@code
     * DefaultVehicleProfileService#requireUsageBelongsToAsset}).
     *
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown, out of scope, or
     *                                          {@code usageId} does not belong to it
     */
    FlightPassport passport(AssetId assetId, UsageId usageId, VisibilityScope scope);

    /**
     * What changed on this aircraft between the flight immediately before {@code usageId} and this
     * one -- config-drift, compared as the previous flight's POSTFLIGHT snapshot against this
     * flight's PREFLIGHT snapshot: the only window in which a parameter write is actually possible
     * (D10's disarmed-only interlock rules out drift occurring in the air). Empty, never thrown,
     * whenever there is no previous flight or either snapshot was never captured -- an honest
     * "nothing to compare", not an error. Finding "the previous flight" does search only the
     * asset's recent usages (unlike {@link #passport}'s own membership check) -- two flights being
     * compared are adjacent by definition, so the previous one is always well inside that window.
     *
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown, out of scope, or
     *                                          {@code usageId} does not belong to it
     */
    List<ParameterDrift> driftFromPreviousFlight(AssetId assetId, UsageId usageId, VisibilityScope scope);
}
