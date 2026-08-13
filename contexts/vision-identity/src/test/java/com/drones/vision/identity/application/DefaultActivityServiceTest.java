package com.drones.vision.identity.application;

import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultActivityServiceTest {

    @Test
    void myActivityReturnsTheActorsAuditEntries() {
        AuditTrailPort auditTrail = mock(AuditTrailPort.class);
        ActivityService service = new DefaultActivityService(auditTrail);
        UserId actor = UserId.random();
        AuditEntry entry = AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                java.util.UUID.randomUUID().toString(), "did a thing");
        when(auditTrail.findByActor(actor, 50)).thenReturn(List.of(entry));

        List<AuditEntry> result = service.myActivity(actor, 50);

        assertEquals(List.of(entry), result);
        verify(auditTrail).findByActor(actor, 50);
    }

    @Test
    void constructorRejectsNullCollaborator() {
        assertThrows(NullPointerException.class, () -> new DefaultActivityService(null));
    }
}
