package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ActionBinding;
import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ControlAction;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultControlProfileService} against a hand-written in-memory repository — the same
 * hand-fake idiom the other application-service tests in this package use.
 */
class DefaultControlProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private static final UserId ALICE = UserId.random();
    private static final UserId BOB = UserId.random();

    private final FakeRepository repository = new FakeRepository();
    private final DefaultControlProfileService service =
            new DefaultControlProfileService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anOperatorWithNothingSavedFliesTheBuiltIn() {
        ControlProfile resolved = service.activeFor(ALICE, VehicleKind.ROVER);

        assertTrue(resolved.isBuiltIn());
        assertEquals(VehicleKind.ROVER, resolved.kind());
        assertEquals(ControlProfile.forKind(VehicleKind.ROVER), resolved);
    }

    @Test
    void everyVehicleKindHasABuiltIn() {
        assertEquals(VehicleKind.values().length, service.builtIns().size());
    }

    @Test
    void aNewProfileIsACopyOfTheBuiltInAndIsNotActiveYet() {
        OwnedControlProfile created = service.create(ALICE, VehicleKind.COPTER, "  Bench copter  ");

        assertEquals("Bench copter", created.profile().displayName());
        assertEquals(VehicleKind.COPTER, created.kind());
        assertEquals(ControlProfile.forKind(VehicleKind.COPTER).channelMap(), created.profile().channelMap());
        assertFalse(created.profile().isBuiltIn());
        assertFalse(created.active());
        assertEquals(NOW, created.updatedAt());
        // Creating does not change what a session would engage with -- activating is its own gesture.
        assertTrue(service.activeFor(ALICE, VehicleKind.COPTER).isBuiltIn());
    }

    @Test
    void activatingMakesASessionUseItInsteadOfTheBuiltIn() {
        OwnedControlProfile created = service.create(ALICE, VehicleKind.ROVER, "Bench rover");

        service.activate(ALICE, created.id());

        ControlProfile resolved = service.activeFor(ALICE, VehicleKind.ROVER);
        assertFalse(resolved.isBuiltIn());
        assertEquals("Bench rover", resolved.displayName());
        // ...and only for that kind, and only for that operator.
        assertTrue(service.activeFor(ALICE, VehicleKind.COPTER).isBuiltIn());
        assertTrue(service.activeFor(BOB, VehicleKind.ROVER).isBuiltIn());
    }

    @Test
    void updatingKeepsIdentityAndActivenessAndRebindsControls() {
        OwnedControlProfile created = service.create(ALICE, VehicleKind.ROVER, "Bench rover");
        service.activate(ALICE, created.id());
        ActionMap actions = new ActionMap(List.of(ActionBinding.pressButton(0, ControlAction.ARM)));

        OwnedControlProfile updated =
                service.update(ALICE, created.id(), "Field rover", created.profile().channelMap(), actions);

        assertEquals(created.id(), updated.id());
        assertEquals("Field rover", updated.profile().displayName());
        assertTrue(updated.active());
        assertEquals(ControlAction.ARM,
                updated.profile().actionMap().find(com.drones.vision.flight.domain.model.ControlBinding.Source.BUTTON, 0)
                        .orElseThrow().positions().getFirst().action());
        assertEquals("Field rover", service.activeFor(ALICE, VehicleKind.ROVER).displayName());
    }

    @Test
    void deletingTheActiveProfileHandsTheVehicleBackToItsBuiltIn() {
        OwnedControlProfile created = service.create(ALICE, VehicleKind.ROVER, "Bench rover");
        service.activate(ALICE, created.id());

        service.delete(ALICE, created.id());

        assertTrue(service.activeFor(ALICE, VehicleKind.ROVER).isBuiltIn());
        assertTrue(service.saved(ALICE).isEmpty());
    }

    @Test
    void oneOperatorCannotTouchAnothersProfile() {
        OwnedControlProfile alices = service.create(ALICE, VehicleKind.ROVER, "Bench rover");

        assertThrows(AccessDeniedException.class, () -> service.delete(BOB, alices.id()));
        assertThrows(AccessDeniedException.class, () -> service.activate(BOB, alices.id()));
        assertThrows(AccessDeniedException.class,
                () -> service.update(BOB, alices.id(), "Mine now", alices.profile().channelMap(), ActionMap.empty()));
    }

    @Test
    void builtInsCannotBeEditedOrDeleted() {
        ControlProfileId builtIn = ControlProfileId.builtIn(VehicleKind.COPTER);

        assertThrows(IllegalArgumentException.class, () -> service.delete(ALICE, builtIn));
        assertThrows(IllegalArgumentException.class, () -> service.activate(ALICE, builtIn));
        assertThrows(IllegalArgumentException.class, () -> new OwnedControlProfile(ALICE,
                ControlProfile.forKind(VehicleKind.COPTER), false, NOW));
    }

    @Test
    void anUnknownProfileIsNotFound() {
        assertThrows(NoSuchElementException.class, () -> service.delete(ALICE, ControlProfileId.random()));
    }

    @Test
    void aBlankNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.create(ALICE, VehicleKind.ROVER, "   "));
    }

    @Test
    void theSelectorSeamIsThisServicesOwnResolution() {
        ControlProfileSelector selector = service::activeFor;

        assertEquals(ControlProfile.forKind(VehicleKind.PLANE).id(),
                selector.forSession(ALICE, VehicleKind.PLANE).id());
        assertTrue(ControlProfileSelector.builtInOnly().forSession(ALICE, VehicleKind.ROVER).isBuiltIn());
    }

    /** In-memory {@link ControlProfileRepositoryPort} honouring the port's own one-active-per-kind rule. */
    private static final class FakeRepository implements ControlProfileRepositoryPort {

        private final List<OwnedControlProfile> rows = new ArrayList<>();

        @Override
        public List<OwnedControlProfile> findAllByOwner(UserId owner) {
            return rows.stream().filter(row -> row.owner().equals(owner))
                    .sorted(Comparator.comparing(OwnedControlProfile::updatedAt).reversed()).toList();
        }

        @Override
        public Optional<OwnedControlProfile> findById(ControlProfileId id) {
            return rows.stream().filter(row -> row.id().equals(id)).findFirst();
        }

        @Override
        public Optional<OwnedControlProfile> findActive(UserId owner, VehicleKind kind) {
            return rows.stream()
                    .filter(row -> row.owner().equals(owner) && row.kind() == kind && row.active())
                    .findFirst();
        }

        @Override
        public void save(OwnedControlProfile profile) {
            rows.removeIf(row -> row.id().equals(profile.id()));
            rows.add(profile);
        }

        @Override
        public void activate(UserId owner, ControlProfileId id) {
            OwnedControlProfile target = findById(id)
                    .orElseThrow(() -> new NoSuchElementException("No control profile " + id.value()));
            List<OwnedControlProfile> next = rows.stream()
                    .map(row -> row.owner().equals(owner) && row.kind() == target.kind()
                            ? new OwnedControlProfile(row.owner(), row.profile(), row.id().equals(id), row.updatedAt())
                            : row)
                    .toList();
            rows.clear();
            rows.addAll(next);
        }

        @Override
        public void delete(ControlProfileId id) {
            rows.removeIf(row -> row.id().equals(id));
        }
    }
}
