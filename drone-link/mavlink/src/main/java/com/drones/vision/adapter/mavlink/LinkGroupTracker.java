package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.session.Dispatcher;
import com.drones.mavlink.session.LinkQuality;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.Subscription;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;

import com.drones.vision.adapter.mavlink.election.LinkElectionSettings;
import com.drones.vision.adapter.mavlink.election.LinkGroup;
import com.drones.vision.adapter.mavlink.election.LinkGroupSnapshot;
import com.drones.vision.adapter.mavlink.election.LinkSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-gateway sighting tracker and {@link LinkGroup} registry (LINK-PAIRING-PLAN.md §3.4/§4 row
 * L3): a second, independent {@link Dispatcher} subscription — every frame heard on the shared
 * gateway's session, regardless of claim, updates the {@link LinkGroup} for its sysid, so election
 * proceeds even for a sysid nothing has claimed yet. Deliberately not folded into {@link
 * MavlinkGateway}'s own claim-routing subscription, matching {@code MavlinkMessageInventory}'s own
 * "a bug in one must never affect the other" precedent (this module's MODULE.md).
 *
 * <p>{@code linkDescriptorLookup} resolves a frame's {@link LinkId} to the {@link LinkDescriptor} it
 * was registered under — supplied by the owning gateway rather than duplicated here, since {@link
 * MavlinkGateway} already keeps that map for its own logging. A frame on a link this tracker has
 * not yet been told the descriptor for (a benign registration race — the reader thread can start
 * before {@code linkDescriptors.put} runs) is silently skipped; the very next frame retries.
 *
 * <p>Package-private: owned entirely by {@link MavlinkGateway}, exactly like {@link
 * MavlinkMessageInventory}.
 */
final class LinkGroupTracker {

    private final LinkElectionSettings settings;
    private final Function<LinkId, LinkDescriptor> linkDescriptorLookup;
    private final LinkQuality linkQuality;
    private final Map<Integer, LinkGroup> groups = new ConcurrentHashMap<>();
    private final Subscription subscription;

    LinkGroupTracker(Dispatcher dispatcher, LinkQuality linkQuality, LinkElectionSettings settings,
                      Function<LinkId, LinkDescriptor> linkDescriptorLookup) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.linkQuality = Objects.requireNonNull(linkQuality, "linkQuality must not be null");
        this.linkDescriptorLookup = Objects.requireNonNull(linkDescriptorLookup, "linkDescriptorLookup must not be null");
        this.subscription = Objects.requireNonNull(dispatcher, "dispatcher must not be null")
                .subscribe(MessageFilter.any(), this::onFrame);
    }

    private void onFrame(MavFrame frame) {
        LinkDescriptor descriptor = linkDescriptorLookup.apply(frame.link());
        if (descriptor == null) {
            return;
        }
        int sysid = frame.header().system().value();
        group(sysid).sight(frame.link(), descriptor, frame.receivedAt());
    }

    /** Forgets {@code link} from every group it belongs to — called from {@link MavlinkGateway#unregister(LinkId)}. */
    void forgetLink(LinkId link) {
        Instant now = Instant.now();
        for (LinkGroup group : groups.values()) {
            group.forget(link, now);
        }
    }

    /** The {@link LinkGroup} for {@code sysid}, or {@code null} if nothing has ever been heard from it. */
    LinkGroup groupFor(int sysid) {
        return groups.get(sysid);
    }

    /** A point-in-time view for {@code sysid}, quality-annotated, or {@code null} if nothing has ever been heard from it. */
    LinkGroupSnapshot snapshot(int sysid) {
        LinkGroup group = groups.get(sysid);
        if (group == null) {
            return null;
        }
        LinkGroupSnapshot base = group.snapshot(Instant.now());
        List<LinkSnapshot> withQuality = base.links().stream()
                .map(s -> new LinkSnapshot(s.id(), s.carrier(), s.serialRole(), s.label(), s.active(),
                        s.receiving(), s.heartbeatAge(), linkQuality.of(s.id())))
                .toList();
        return new LinkGroupSnapshot(base.sysid(), withQuality, base.activeLinkId(), base.pinned(), base.lastFailoverAt());
    }

    private LinkGroup group(int sysid) {
        return groups.computeIfAbsent(sysid, id -> new LinkGroup(id, settings));
    }

    void close() {
        subscription.close();
    }
}
