package com.drones.vision.adapter.mavlink.election;

import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.SerialRole;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-sysid link election state machine (LINK-PAIRING-PLAN.md §3.4/§4 row L3, docs/conclusions/
 * link-research/05-multi-link-arbitration.md §3): tracks every {@link LinkId} that has ever
 * delivered a frame for one MAVLink system id over the shared gateway (lobby plus every
 * carrier-registered link — a legacy per-descriptor UDP gateway is out of this group's scope, since
 * nothing else ever shares its link), and elects which one, if any, is ACTIVE for control TX.
 *
 * <h2>Election rules</h2>
 * <ul>
 *   <li>Only links heard within {@link LinkElectionSettings#softTimeout()} are eligible to win;</li>
 *   <li>{@link SerialRole#BENCH} is never auto-elected — reachable only via {@link #pin};</li>
 *   <li>among eligible links, {@link LinkDescriptor#priority()} decides (higher wins) — a SERIAL
 *       {@link SerialRole#GROUND_RADIO} descriptor registers above a UDP one by carrying a higher
 *       {@code priority}, not by any carrier-kind rule this class hardcodes;</li>
 *   <li>a healthy active link is not displaced by a recovered higher-priority link until that link
 *       has stayed the best eligible candidate continuously for {@link
 *       LinkElectionSettings#dwellWindow()} — "no flapping on one missed heartbeat";</li>
 *   <li>an active link past its soft timeout is demoted immediately if a healthy alternative
 *       exists; with none, it is kept — a stale link beats no link (CLAUDE.md rule 7) — until
 *       {@link LinkElectionSettings#hardTimeout()}, after which there is no active link at all;</li>
 *   <li>{@link #pin}/{@link #release} are an operator override layered on top of all the above:
 *       pin wins regardless of health or {@link SerialRole#BENCH}; release re-runs automatic
 *       election immediately.</li>
 * </ul>
 *
 * <p>{@link #lastFailoverAt()} only ever advances from an <b>automatic</b> transition inside {@link
 * #sight}/{@link #tick}/{@link #forget}/the automatic re-election {@link #release} performs — never
 * from {@link #pin} itself, matching the web contract's "most recent automatic failover" doc
 * comment.
 *
 * <p>Every method is {@code synchronized}: one sysid's frames may arrive concurrently on several
 * links' own reader threads (each link owns its own {@code mavlink-rx-*} thread, see {@code
 * MavlinkSession}), and a station operator's pin/release call arrives on a request thread
 * independently of all of them.
 */
public final class LinkGroup {

    private final int sysid;
    private final LinkElectionSettings settings;
    private final Map<LinkId, LinkDescriptor> members = new LinkedHashMap<>();
    private final Map<LinkId, Instant> lastHeard = new LinkedHashMap<>();

    private LinkId pinnedLinkId;
    private LinkId activeLinkId;
    private Instant lastFailoverAt;
    private LinkId reclaimCandidate;
    private Instant reclaimSince;

    public LinkGroup(int sysid, LinkElectionSettings settings) {
        this.sysid = sysid;
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    public int sysid() {
        return sysid;
    }

    /** Records a sighting of this sysid on {@code link} at {@code when}, then re-runs election. */
    public synchronized void sight(LinkId link, LinkDescriptor descriptor, Instant when) {
        Objects.requireNonNull(link, "link must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(when, "when must not be null");
        members.put(link, descriptor);
        lastHeard.put(link, when);
        elect(when);
    }

    /** Forgets {@code link} entirely (it was unregistered, e.g. a hotplug-removed radio), then re-elects. */
    public synchronized void forget(LinkId link, Instant now) {
        Objects.requireNonNull(link, "link must not be null");
        Objects.requireNonNull(now, "now must not be null");
        members.remove(link);
        lastHeard.remove(link);
        if (link.equals(pinnedLinkId)) {
            pinnedLinkId = null;
        }
        if (link.equals(reclaimCandidate)) {
            clearReclaim();
        }
        if (link.equals(activeLinkId)) {
            activeLinkId = null;
        }
        elect(now);
    }

    /**
     * Operator override: forces {@code link} ACTIVE regardless of health or {@link
     * SerialRole#BENCH}. Deliberately bypasses every automatic-election rule above — an operator
     * plugged into the bench cable knows better than the election heuristic.
     *
     * @throws IllegalArgumentException if {@code link} has never been sighted for this sysid
     */
    public synchronized void pin(LinkId link) {
        Objects.requireNonNull(link, "link must not be null");
        if (!members.containsKey(link)) {
            throw new IllegalArgumentException("link " + link + " is not a known member of sysid " + sysid);
        }
        pinnedLinkId = link;
        activeLinkId = link;
        clearReclaim();
    }

    /**
     * Releases an operator pin (a no-op if not pinned) and hands control back to automatic election
     * <b>immediately</b> — deliberately bypassing {@link #elect}'s usual dwell-gated reclaim: a pin
     * was itself already an override, so there is no previously-healthy automatic choice left to
     * protect from flapping. If the best eligible candidate is already the active link (including
     * the just-released pinned one), nothing changes and {@link #lastFailoverAt()} does not move.
     */
    public synchronized void release(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        pinnedLinkId = null;
        clearReclaim();
        LinkId best = bestEligible(now);
        if (best != null && !best.equals(activeLinkId)) {
            promote(best, now);
        } else if (best == null && activeLinkId != null && !isHardAlive(activeLinkId, now)) {
            activeLinkId = null;
            lastFailoverAt = now;
        }
        // Else: activeLinkId already matches best (or best is null but activeLinkId is still within
        // its hard-timeout grace) -- nothing observably changed, so lastFailoverAt must not move.
    }

    /** Re-runs election against {@code now} without a new sighting — call periodically to catch timeouts passing between frames. */
    public synchronized void tick(Instant now) {
        elect(Objects.requireNonNull(now, "now must not be null"));
    }

    public synchronized boolean pinned() {
        return pinnedLinkId != null;
    }

    public synchronized LinkId activeLinkId() {
        return activeLinkId;
    }

    public synchronized Instant lastFailoverAt() {
        return lastFailoverAt;
    }

    /** A point-in-time view of every member link's election state — quality is left {@code null}; the caller fills it in. */
    public synchronized LinkGroupSnapshot snapshot(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        List<LinkSnapshot> links = new ArrayList<>(members.size());
        for (Map.Entry<LinkId, LinkDescriptor> entry : members.entrySet()) {
            LinkId id = entry.getKey();
            LinkDescriptor descriptor = entry.getValue();
            Duration age = Duration.between(lastHeard.get(id), now);
            links.add(new LinkSnapshot(id, descriptor.carrier(), descriptor.serialRole(), descriptor.label(),
                    id.equals(activeLinkId), isSoftHealthy(id, now), age, null));
        }
        return new LinkGroupSnapshot(sysid, links, activeLinkId, pinnedLinkId != null, lastFailoverAt);
    }

    private void elect(Instant now) {
        if (pinnedLinkId != null) {
            activeLinkId = members.containsKey(pinnedLinkId) ? pinnedLinkId : null;
            return;
        }
        LinkId best = bestEligible(now);
        if (activeLinkId == null) {
            if (best != null) {
                promote(best, now);
            }
            return;
        }
        if (isSoftHealthy(activeLinkId, now)) {
            if (best != null && !best.equals(activeLinkId) && outranks(best, activeLinkId)) {
                trackReclaim(best, now);
                if (dwellElapsed(now)) {
                    promote(best, now);
                }
            } else {
                clearReclaim();
            }
            return;
        }
        // Active link is stale past its soft timeout: demote immediately if a healthy alternative exists.
        if (best != null && !best.equals(activeLinkId)) {
            promote(best, now);
            return;
        }
        if (!isHardAlive(activeLinkId, now)) {
            activeLinkId = null;
            lastFailoverAt = now;
            clearReclaim();
        }
        // Else: stale but within hard-timeout grace and no alternative -- kept as-is. Failsafe honesty
        // (CLAUDE.md rule 7): a degraded link beats no link until the hard bound actually expires.
    }

    private void promote(LinkId link, Instant now) {
        activeLinkId = link;
        lastFailoverAt = now;
        clearReclaim();
    }

    private void trackReclaim(LinkId candidate, Instant now) {
        if (!candidate.equals(reclaimCandidate)) {
            reclaimCandidate = candidate;
            reclaimSince = now;
        }
    }

    private void clearReclaim() {
        reclaimCandidate = null;
        reclaimSince = null;
    }

    private boolean dwellElapsed(Instant now) {
        return reclaimSince != null && Duration.between(reclaimSince, now).compareTo(settings.dwellWindow()) >= 0;
    }

    private boolean outranks(LinkId a, LinkId b) {
        return members.get(a).priority() > members.get(b).priority();
    }

    private LinkId bestEligible(Instant now) {
        LinkId best = null;
        for (Map.Entry<LinkId, LinkDescriptor> entry : members.entrySet()) {
            LinkId candidate = entry.getKey();
            LinkDescriptor descriptor = entry.getValue();
            if (descriptor.serialRole() == SerialRole.BENCH) {
                continue;
            }
            if (!isSoftHealthy(candidate, now)) {
                continue;
            }
            if (best == null || descriptor.priority() > members.get(best).priority()) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isSoftHealthy(LinkId link, Instant now) {
        Instant heard = lastHeard.get(link);
        return heard != null && Duration.between(heard, now).compareTo(settings.softTimeout()) <= 0;
    }

    private boolean isHardAlive(LinkId link, Instant now) {
        Instant heard = lastHeard.get(link);
        return heard != null && Duration.between(heard, now).compareTo(settings.hardTimeout()) <= 0;
    }
}
