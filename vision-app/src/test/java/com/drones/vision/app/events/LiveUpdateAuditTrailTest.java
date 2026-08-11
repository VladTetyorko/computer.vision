package com.drones.vision.app.events;

import com.drones.vision.identity.domain.model.AuditAction;
import com.drones.vision.identity.domain.model.AuditEntry;
import com.drones.vision.identity.domain.model.AuditTargetType;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AuditTrailPort;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link LiveUpdateAuditTrail} (docs/plans/done/REALTIME-PLAN.md §4) — no Spring context.
 */
class LiveUpdateAuditTrailTest {

    private AuditTrailPort delegate;
    private LiveUpdatePublisherPort liveUpdatePublisherPort;
    private LiveUpdateAuditTrail auditTrail;

    @BeforeEach
    void setUp() {
        delegate = mock(AuditTrailPort.class);
        liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
        auditTrail = new LiveUpdateAuditTrail(delegate, liveUpdatePublisherPort);
    }

    @Test
    void recordDelegatesAndAnnouncesAFleetChangedUpdate() {
        AuditEntry entry = AuditEntry.of(UserId.random(), AuditAction.CREATED, AuditTargetType.ASSET,
                "asset-1", "created");
        when(delegate.record(entry)).thenReturn(entry);

        AuditEntry recorded = auditTrail.record(entry);

        assertEquals(entry, recorded);
        verify(delegate).record(entry);
        verify(liveUpdatePublisherPort).publishFleetChanged();
    }

    @Test
    void findRecentAndFindByTargetAreThinPassThroughsWithNoLiveUpdateAnnouncement() {
        auditTrail.findRecent(10);
        auditTrail.findByTarget(AuditTargetType.DEVICE, "device-1", 10);

        verify(delegate).findRecent(10);
        verify(delegate).findByTarget(AuditTargetType.DEVICE, "device-1", 10);
        verifyNoInteractions(liveUpdatePublisherPort);
    }
}
