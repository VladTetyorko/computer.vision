package com.drones.vision.flight.application.link;

import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.flight.domain.port.LinkStateLiveUpdatePort;
import com.drones.vision.flight.domain.port.VehicleLinkPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one implementation of {@link LinkStateService} (LINK-PAIRING-PLAN.md §3.4 frozen contract),
 * matching {@code DefaultGeofenceService}'s own constructor idiom — collaborators, not overload
 * chains.
 *
 * <h2>Exactly one {@code LINK_FAILOVER} per active-link change</h2>
 * Every public method here ends by calling {@link #observe}, which (1) always republishes the fresh
 * snapshot on {@link LinkStateLiveUpdatePort} — "one topic, one full-state payload" — and (2) keeps
 * this instance's own last-observed {@code activeLinkId} per asset ({@link #lastKnownActive}) so it
 * can tell a genuine change from a repeat read. Automatic elections happen continuously inside the
 * adapter's own {@code LinkGroup}, entirely outside this service's control; this service only ever
 * <i>discovers</i> such a change the next time something calls {@link #linksFor} (e.g. the live-
 * update seed path, a station poll, or the very {@link #pin}/{@link #release} calls below, which
 * both re-read after mutating). The very first observation of a given asset only establishes the
 * baseline — a cold cache catching up to whatever state the adapter is already in is not itself a
 * "failover" — every observation after that fires {@link EventType#LINK_FAILOVER} exactly once per
 * actual change and never for a repeat of the same value.
 *
 * <h2>Publishing on change, not only on read (docs/plans/active/LINK-PAIRING-PLAN.md §8 defect #5)</h2>
 * {@link #observe} is also reached from a second direction: the constructor subscribes {@link
 * #onAutomaticGroupChange} to {@link VehicleLinkPort#onGroupChanged}, so an election change the
 * adapter discovers on its own (a link appearing/disappearing, or the ACTIVE link changing) reaches
 * {@link LinkStateLiveUpdatePort}/{@code EventType#LINK_FAILOVER} the same way a request-driven read
 * does, instead of sitting unpublished until the next station poll or operator action happens to hit
 * {@link #linksFor}. That subscription fires from the adapter's own frame-reader thread, which is why
 * {@link #lastKnownActive} is a {@link ConcurrentHashMap} and {@link #onAutomaticGroupChange} never
 * lets an exception escape back into that thread.
 */
public final class DefaultLinkStateService implements LinkStateService {

    private static final System.Logger LOG = System.getLogger(DefaultLinkStateService.class.getName());

    private final VehicleLinkPort vehicleLinkPort;
    private final LinkStateLiveUpdatePort liveUpdatePort;
    private final EventPublisherPort eventPublisher;
    private final Map<AssetId, Optional<LinkId>> lastKnownActive = new ConcurrentHashMap<>();

    public DefaultLinkStateService(VehicleLinkPort vehicleLinkPort, LinkStateLiveUpdatePort liveUpdatePort,
                                    EventPublisherPort eventPublisher) {
        this.vehicleLinkPort = Objects.requireNonNull(vehicleLinkPort, "vehicleLinkPort must not be null");
        this.liveUpdatePort = Objects.requireNonNull(liveUpdatePort, "liveUpdatePort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.vehicleLinkPort.onGroupChanged(this::onAutomaticGroupChange);
    }

    @Override
    public LinkGroupView linksFor(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        return observe(assetId, vehicleLinkPort.linksFor(assetId), "auto");
    }

    @Override
    public LinkGroupView pin(AssetId assetId, LinkId linkId, UserId actorId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(linkId, "linkId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        vehicleLinkPort.pin(assetId, linkId);
        return observe(assetId, vehicleLinkPort.linksFor(assetId), "operator");
    }

    @Override
    public LinkGroupView release(AssetId assetId, UserId actorId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        vehicleLinkPort.release(assetId);
        return observe(assetId, vehicleLinkPort.linksFor(assetId), "operator");
    }

    /**
     * The listener registered against {@link VehicleLinkPort#onGroupChanged}: re-reads and
     * re-publishes {@code assetId}'s snapshot through the same {@link #observe} path {@link
     * #linksFor} uses. Called from the adapter's own frame-reader thread (see class javadoc) — a
     * failure here (the live-update port throwing, a resolution error) must never propagate back
     * into that thread, so it is caught and dropped: the next automatic change, or the next explicit
     * read, recovers whatever this one missed.
     */
    private void onAutomaticGroupChange(AssetId assetId) {
        try {
            linksFor(assetId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to publish an automatic link-group change for asset "
                    + assetId.value(), e);
        }
    }

    private LinkGroupView observe(AssetId assetId, LinkGroupView snapshot, String reason) {
        liveUpdatePort.publishLinks(assetId, snapshot);
        LinkId current = snapshot.activeLinkId();
        Optional<LinkId> previous = lastKnownActive.put(assetId, Optional.ofNullable(current));
        if (previous != null && !previous.equals(Optional.ofNullable(current))) {
            publishFailover(assetId, previous.orElse(null), current, reason);
        }
        return snapshot;
    }

    private void publishFailover(AssetId assetId, LinkId from, LinkId to, String reason) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("assetId", assetId.value().toString());
        if (from != null) {
            attributes.put("fromLinkId", from.value());
        }
        if (to != null) {
            attributes.put("toLinkId", to.value());
        }
        attributes.put("reason", reason);
        Event event = new Event(UUID.randomUUID().toString(), null, Instant.now(), EventType.LINK_FAILOVER,
                "Active link changed for asset " + assetId.value(), attributes);
        eventPublisher.publish(event);
    }
}
