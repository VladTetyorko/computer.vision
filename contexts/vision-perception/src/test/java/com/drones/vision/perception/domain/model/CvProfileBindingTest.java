package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CvProfileBindingTest {

    private static final CvProfileId PROFILE_ID = CvProfileId.random();
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void acceptsValidBinding() {
        CvProfileBinding binding = new CvProfileBinding(BindingScope.ASSET, "asset-1", PROFILE_ID, NOW);

        assertEquals(BindingScope.ASSET, binding.scopeKind());
        assertEquals("asset-1", binding.scopeId());
        assertEquals(PROFILE_ID, binding.profileId());
        assertEquals(NOW, binding.createdAt());
    }

    @Test
    void rejectsNullScopeKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfileBinding(null, "asset-1", PROFILE_ID, NOW));
    }

    @Test
    void rejectsBlankScopeId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfileBinding(BindingScope.ASSET, null, PROFILE_ID, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfileBinding(BindingScope.ASSET, "", PROFILE_ID, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfileBinding(BindingScope.ASSET, "   ", PROFILE_ID, NOW));
    }

    @Test
    void rejectsNullProfileId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfileBinding(BindingScope.CATEGORY, "fpv-drone", null, NOW));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvProfileBinding(BindingScope.ORGANIZATION, "group-1", PROFILE_ID, null));
    }
}
