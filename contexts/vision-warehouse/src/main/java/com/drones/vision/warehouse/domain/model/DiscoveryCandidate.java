package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamDescriptor;

import java.time.Instant;

/**
 * A persisted discovery candidate — the "found devices" inbox row (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 Z2a).
 *
 * <p>Distinct from {@link DiscoveredDevice}, which is the transient, in-memory result of one scan:
 * this record is what survives across scans and across a station restart, deduplicated by {@link
 * #identityKey()} and carrying the operator's own decision ({@link #status()}) about it. {@link
 * com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#report} is the only
 * writer of {@link #discovered()}/{@link #lastSeen()}; {@link #status()}/{@link #registeredAsset()}
 * change only through this record's own transition methods, never directly.
 *
 * <h2>Identity</h2>
 * {@link #identityKey()} is {@link #identityKeyFor(DiscoveredDevice)} — {@code method + "|" +
 * address}, plus {@code "|sysid=" + n} when the discovered device carries one — computed once at
 * {@link #newlyReported} time and carried unchanged afterward (the discovered device's own address
 * can legitimately drift across reports, e.g. a rebroadcast on a new ephemeral port; the identity
 * key must not).
 *
 * <h2>{@code registeredAsset} may be {@code null} even when {@link #status()} is {@link
 * CandidateStatus#REGISTERED}</h2>
 * A candidate becomes {@link CandidateStatus#REGISTERED} whenever it matches an already-registered
 * <em>device</em> (see {@link
 * com.drones.vision.warehouse.application.asset.AssetService#findDuplicateDevice}); that device is
 * not always owned by an asset yet (the same "already registered to device ..." case {@link
 * com.drones.vision.warehouse.application.asset.AssetService#createFromCandidate}'s error message
 * names). {@link #registeredAsset()} is only ever non-{@code null} when an owning asset is actually
 * known.
 *
 * @param id             this candidate's identity
 * @param identityKey    the dedup key {@link #report}s upsert by; see {@link
 *                       #identityKeyFor(DiscoveredDevice)}
 * @param discovered     the most recently reported scan result for this identity
 * @param firstSeen      when this identity was first reported
 * @param lastSeen       when this identity was most recently reported
 * @param status         where this candidate stands with an operator
 * @param registeredAsset the asset this candidate resolved to, or {@code null} if none is known
 */
public record DiscoveryCandidate(DiscoveryCandidateId id, String identityKey, DiscoveredDevice discovered,
                                  Instant firstSeen, Instant lastSeen, CandidateStatus status,
                                  AssetId registeredAsset) {

    public DiscoveryCandidate {
        if (id == null) {
            throw new IllegalArgumentException("DiscoveryCandidate id must not be null");
        }
        if (identityKey == null || identityKey.isBlank()) {
            throw new IllegalArgumentException("DiscoveryCandidate identityKey must not be blank");
        }
        if (discovered == null) {
            throw new IllegalArgumentException("DiscoveryCandidate discovered must not be null");
        }
        if (firstSeen == null) {
            throw new IllegalArgumentException("DiscoveryCandidate firstSeen must not be null");
        }
        if (lastSeen == null) {
            throw new IllegalArgumentException("DiscoveryCandidate lastSeen must not be null");
        }
        if (lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException("DiscoveryCandidate lastSeen must not be before firstSeen");
        }
        if (status == null) {
            throw new IllegalArgumentException("DiscoveryCandidate status must not be null");
        }
        if (registeredAsset != null && status != CandidateStatus.REGISTERED) {
            throw new IllegalArgumentException(
                    "DiscoveryCandidate registeredAsset must be null unless status is REGISTERED");
        }
    }

    /**
     * The identity {@link com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#report}
     * upserts by: {@code method + "|" + address}, plus {@code "|sysid=" + n} when a sysid is
     * carried. {@code sysid} is read the same lenient way {@link
     * com.drones.vision.warehouse.application.asset.AssetService#createFromCandidate}'s duplicate
     * check already reads it — {@link StreamDescriptor#options()}'s {@code "sysid"} key, a raw
     * string, never parsed or format-validated — preferring {@link
     * DiscoveredDevice#suggestedStream()} when present and falling back to {@link
     * DiscoveredDevice#details()}'s own {@code "sysid"} entry (a candidate that has not yet resolved
     * a ready-to-use stream, e.g. ONVIF before {@code GetStreamUri}, still carries the sysid there —
     * see {@code MavlinkHeartbeatScanner}, which sets both).
     *
     * @param discovered the scan result to key
     * @return the dedup key
     */
    public static String identityKeyFor(DiscoveredDevice discovered) {
        if (discovered == null) {
            throw new IllegalArgumentException("DiscoveryCandidate identityKeyFor: discovered must not be null");
        }
        String key = discovered.method() + "|" + discovered.address();
        String sysid = sysid(discovered);
        return sysid != null ? key + "|sysid=" + sysid : key;
    }

    private static String sysid(DiscoveredDevice discovered) {
        StreamDescriptor stream = discovered.suggestedStream();
        if (stream != null) {
            String fromStream = stream.options().get("sysid");
            if (fromStream != null) {
                return fromStream;
            }
        }
        return discovered.details().get("sysid");
    }

    /**
     * Creates a brand-new candidate for an identity never reported before: {@link CandidateStatus#NEW},
     * {@link #firstSeen()} and {@link #lastSeen()} both stamped {@code now}.
     *
     * @param id         a fresh id for this candidate
     * @param discovered the scan result that revealed this identity
     * @param now        when it was first reported
     * @return the new candidate
     */
    public static DiscoveryCandidate newlyReported(DiscoveryCandidateId id, DiscoveredDevice discovered,
                                                    Instant now) {
        return new DiscoveryCandidate(id, identityKeyFor(discovered), discovered, now, now, CandidateStatus.NEW,
                null);
    }

    /**
     * A copy refreshed by a later report of the same identity: {@link #discovered()} replaced and
     * {@link #lastSeen()} advanced to {@code now}; {@link #firstSeen()}, {@link #status()} and
     * {@link #registeredAsset()} carried over unchanged (a re-report never revives a {@link
     * CandidateStatus#DISMISSED} candidate, and never un-registers a {@link
     * CandidateStatus#REGISTERED} one, on its own).
     *
     * @param discovered the newly reported scan result
     * @param now        when it was reported
     * @return the refreshed candidate
     */
    public DiscoveryCandidate reSeen(DiscoveredDevice discovered, Instant now) {
        return new DiscoveryCandidate(id, identityKey, discovered, firstSeen, now, status, registeredAsset);
    }

    /**
     * A copy an operator dismissed: {@link #status()} becomes {@link CandidateStatus#DISMISSED},
     * {@link #registeredAsset()} cleared.
     *
     * @return the dismissed candidate
     */
    public DiscoveryCandidate dismiss() {
        return new DiscoveryCandidate(id, identityKey, discovered, firstSeen, lastSeen, CandidateStatus.DISMISSED,
                null);
    }

    /**
     * A copy recorded as already registered: {@link #status()} becomes {@link
     * CandidateStatus#REGISTERED}. Used both when an operator explicitly registers this candidate
     * and when a later report finds it already matches a registered device.
     *
     * @param owningAsset the asset now known to own the matching device, or {@code null} if the
     *                    matching device is not yet assigned to one
     * @return the registered candidate
     */
    public DiscoveryCandidate registeredTo(AssetId owningAsset) {
        return new DiscoveryCandidate(id, identityKey, discovered, firstSeen, lastSeen, CandidateStatus.REGISTERED,
                owningAsset);
    }
}
