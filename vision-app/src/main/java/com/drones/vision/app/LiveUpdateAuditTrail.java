package com.drones.vision.app;

import com.drones.vision.domain.model.AuditEntry;
import com.drones.vision.domain.model.AuditTargetType;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;

import java.util.List;
import java.util.Objects;

/**
 * {@link AuditTrailPort} decorator that additionally announces a live "fleet changed" update
 * (docs/REALTIME-PLAN.md §4) for every recorded entry.
 *
 * <p>Every asset/device mutation ({@code create}/{@code update}/{@code setState}/{@code delete}/
 * {@code assignDevice}/{@code unassignDevice} — see {@code DefaultAssetService}/{@code
 * DefaultDeviceService}, vision-application) already writes exactly one {@link AuditEntry} through
 * this port, whether or not it also raises a domain {@link com.drones.vision.domain.model.Event}
 * (e.g. plain edits/deletes never do) — making this the one uniform seam for "an asset or device
 * changed", without adding a new constructor dependency to either service (both already sit at
 * their constructor-parameter ceiling; see {@code .claude/skills/java-clean-code/SKILL.md} §3).
 * {@link LiveUpdateEventPublisher} covers the complementary "stream/device online-offline"
 * lifecycle, which flows through {@code EventPublisherPort} instead and never touches this port.
 *
 * <p>Only wired ({@link WiringConfiguration#auditTrailPort}) when {@code vision.live.enabled} is
 * {@code true}; with it disabled, the plain delegate is used directly and this class is never
 * constructed.
 */
public final class LiveUpdateAuditTrail implements AuditTrailPort {

    private final AuditTrailPort delegate;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;

    public LiveUpdateAuditTrail(AuditTrailPort delegate, LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.liveUpdatePublisherPort =
                Objects.requireNonNull(liveUpdatePublisherPort, "liveUpdatePublisherPort must not be null");
    }

    @Override
    public AuditEntry record(AuditEntry entry) {
        AuditEntry recorded = delegate.record(entry);
        liveUpdatePublisherPort.publishFleetChanged();
        return recorded;
    }

    @Override
    public List<AuditEntry> findRecent(int limit) {
        return delegate.findRecent(limit);
    }

    @Override
    public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
        return delegate.findByTarget(targetType, targetId, limit);
    }

    @Override
    public List<AuditEntry> findByActor(UserId actor, int limit) {
        return delegate.findByActor(actor, limit);
    }
}
