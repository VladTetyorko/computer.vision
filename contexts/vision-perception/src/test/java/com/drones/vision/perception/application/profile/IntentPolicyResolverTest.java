package com.drones.vision.perception.application.profile;

import com.drones.vision.perception.domain.model.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentPolicyResolver}: the platform tier of the profile fold (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7, wave W2.6) — every {@link Intent} value resolves to a fixed,
 * hand-verified {@link IntentPolicy}, and {@link Intent#CUSTOM} is the one value whose {@code
 * classSet} is never a platform-fixed seed at all, but the caller's own list, untouched.
 */
class IntentPolicyResolverTest {

    @Test
    void peopleResolvesToTheClosedSetModelAndExactlyThePersonClass() {
        IntentPolicy resolved = IntentPolicyResolver.resolve(Intent.PEOPLE, List.of());

        assertEquals("yolo26n.pt", resolved.model().id());
        assertEquals(Set.of("person"), resolved.classSet());
        assertEquals(0.40, resolved.detectFloor());
        assertEquals(0.5, resolved.reportThreshold());
        assertEquals(10, resolved.rateCeiling());
    }

    @Test
    void vehiclesResolvesToTheClosedSetModelAndTheFourVehicleClasses() {
        IntentPolicy resolved = IntentPolicyResolver.resolve(Intent.VEHICLES, List.of());

        assertEquals("yolo26n.pt", resolved.model().id());
        assertEquals(Set.of("car", "truck", "bus", "motorcycle"), resolved.classSet());
        assertEquals(0.40, resolved.detectFloor());
        assertEquals(0.5, resolved.reportThreshold());
        assertEquals(10, resolved.rateCeiling());
    }

    @Test
    void everythingResolvesToTheOpenVocabModelAndAnEmptyClassSetMeaningEveryLabel() {
        IntentPolicy resolved = IntentPolicyResolver.resolve(Intent.EVERYTHING, List.of());

        assertEquals("yoloe-26s-seg-pf.pt", resolved.model().id());
        assertTrue(resolved.classSet().isEmpty(), "EVERYTHING's empty classSet means 'every label,' not 'no labels'");
        assertEquals(0.30, resolved.detectFloor());
        assertEquals(0.5, resolved.reportThreshold());
        assertEquals(4, resolved.rateCeiling(), "a wide open-vocab search needs a lower rate ceiling than a narrow one");
    }

    @Test
    void customResolvesToTheOpenVocabModelAndReturnsTheCallersOwnClassListUntouched() {
        List<String> callerClasses = List.of("forklift", "pallet-jack");

        IntentPolicy resolved = IntentPolicyResolver.resolve(Intent.CUSTOM, callerClasses);

        assertEquals("yoloe-26s-seg-pf.pt", resolved.model().id());
        // "Untouched" is the point of this assertion: CUSTOM carries no fixed class set of its own
        // (Intent's own javadoc) -- the resolver must echo the caller's list back exactly, never
        // substitute, reorder-and-drop-duplicates in a way that loses information, or fall back to
        // a platform default the way PEOPLE/VEHICLES/EVERYTHING do.
        assertEquals(Set.copyOf(callerClasses), resolved.classSet());
        assertEquals(0.40, resolved.detectFloor());
        assertEquals(0.5, resolved.reportThreshold());
        assertEquals(10, resolved.rateCeiling());
    }

    @Test
    void customWithNullClassesThrowsRatherThanInventingASeed() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> IntentPolicyResolver.resolve(Intent.CUSTOM, null));

        assertTrue(thrown.getMessage().contains("customClasses"));
    }

    @Test
    void customWithEmptyClassesThrowsRatherThanInventingASeed() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> IntentPolicyResolver.resolve(Intent.CUSTOM, List.of()));

        assertTrue(thrown.getMessage().contains("customClasses"));
    }

    @Test
    void nonCustomIntentsIgnoreWhateverCustomClassesIsPassed() {
        // PEOPLE/VEHICLES/EVERYTHING never consult customClasses at all -- a non-empty list must not
        // leak into their fixed, platform-owned classSet.
        IntentPolicy resolved = IntentPolicyResolver.resolve(Intent.PEOPLE, List.of("this-must-be-ignored"));

        assertEquals(Set.of("person"), resolved.classSet());
    }

    @Test
    void nullIntentThrows() {
        assertThrows(IllegalArgumentException.class, () -> IntentPolicyResolver.resolve(null, List.of()));
    }
}
