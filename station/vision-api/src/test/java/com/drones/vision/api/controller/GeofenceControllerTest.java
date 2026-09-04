package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.flight.application.geofence.GeofenceService;
import com.drones.vision.flight.application.geofence.GeofenceZoneSpec;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.flight.domain.model.GeofenceZone;
import com.drones.vision.flight.domain.model.ZoneId;
import com.drones.vision.flight.domain.model.ZoneKind;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.identity.domain.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GeofenceControllerTest {

    private GeofenceService geofenceService;
    private MockMvc mockMvc;

    /** Unbounded (auth-off-equivalent) by default, so every pre-existing test below is unaffected. */
    private final CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));

    @BeforeEach
    void setUp() {
        geofenceService = mock(GeofenceService.class);
        mockMvc = mockMvcFor(currentUser);
    }

    /**
     * A {@link MockMvc} bound to a fresh {@link GeofenceController} acting as {@code user} — same
     * mocked {@link #geofenceService}, only the acting {@link CurrentUser} changes
     * (docs/plans/done/LIVE-SCOPE-PLAN.md §2.2, W5's {@code canAdminister()} authority tests below).
     */
    private MockMvc mockMvcFor(CurrentUser user) {
        return MockMvcBuilders.standaloneSetup(new GeofenceController(geofenceService, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static GeofenceZone zone(ZoneId id, String name, ZoneKind kind, Double maxAltitudeMeters,
                                      boolean enabled) {
        List<GeoPosition> polygon = List.of(
                new GeoPosition(10.0, 20.0, null),
                new GeoPosition(10.0, 21.0, null),
                new GeoPosition(11.0, 20.5, null));
        return new GeofenceZone(id, name, kind, polygon, maxAltitudeMeters, enabled);
    }

    @Test
    void listReturns200WithMappedZonesInServiceOrder() throws Exception {
        ZoneId first = ZoneId.random();
        ZoneId second = ZoneId.random();
        when(geofenceService.zones()).thenReturn(List.of(
                zone(first, "Airport keep-out", ZoneKind.KEEP_OUT, 50.0, true),
                zone(second, "Site keep-in", ZoneKind.KEEP_IN, null, false)));

        mockMvc.perform(get("/api/geofences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.value().toString()))
                .andExpect(jsonPath("$[0].name").value("Airport keep-out"))
                .andExpect(jsonPath("$[0].kind").value("KEEP_OUT"))
                .andExpect(jsonPath("$[0].polygon", hasSize(3)))
                .andExpect(jsonPath("$[0].polygon[0].latitude").value(10.0))
                .andExpect(jsonPath("$[0].polygon[0].longitude").value(20.0))
                .andExpect(jsonPath("$[0].maxAltitudeMeters").value(50.0))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].id").value(second.value().toString()))
                .andExpect(jsonPath("$[1].kind").value("KEEP_IN"))
                .andExpect(jsonPath("$[1].maxAltitudeMeters").doesNotExist())
                .andExpect(jsonPath("$[1].enabled").value(false));
    }

    @Test
    void listReturns200WithEmptyListWhenNoZones() throws Exception {
        when(geofenceService.zones()).thenReturn(List.of());

        mockMvc.perform(get("/api/geofences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void createReturns201WithCreatedZone() throws Exception {
        ZoneId created = ZoneId.random();
        when(geofenceService.create(any(GeofenceZoneSpec.class)))
                .thenReturn(zone(created, "New zone", ZoneKind.KEEP_OUT, null, true));

        String body = """
                {
                  "name": "New zone",
                  "kind": "keep_out",
                  "polygon": [
                    {"latitude": 10.0, "longitude": 20.0},
                    {"latitude": 10.0, "longitude": 21.0},
                    {"latitude": 11.0, "longitude": 20.5}
                  ],
                  "enabled": true
                }
                """;

        mockMvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.value().toString()))
                .andExpect(jsonPath("$.name").value("New zone"))
                .andExpect(jsonPath("$.kind").value("KEEP_OUT"));

        ArgumentCaptor<GeofenceZoneSpec> captor = ArgumentCaptor.forClass(GeofenceZoneSpec.class);
        verify(geofenceService).create(captor.capture());
        assertEquals(ZoneKind.KEEP_OUT, captor.getValue().kind());
        assertEquals(3, captor.getValue().polygon().size());
        assertEquals(true, captor.getValue().enabled());
    }

    @Test
    void createReturns400ForAPolygonWithFewerThanThreeVertices() throws Exception {
        String body = """
                {
                  "name": "Too small",
                  "kind": "keep_out",
                  "polygon": [
                    {"latitude": 10.0, "longitude": 20.0},
                    {"latitude": 10.0, "longitude": 21.0}
                  ],
                  "enabled": true
                }
                """;

        mockMvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void createReturns400ForAnUnrecognizedKind() throws Exception {
        String body = """
                {
                  "name": "Bad kind",
                  "kind": "sideways",
                  "polygon": [
                    {"latitude": 10.0, "longitude": 20.0},
                    {"latitude": 10.0, "longitude": 21.0},
                    {"latitude": 11.0, "longitude": 20.5}
                  ],
                  "enabled": true
                }
                """;

        mockMvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void updateReturns200WithUpdatedZone() throws Exception {
        ZoneId id = ZoneId.random();
        when(geofenceService.update(eq(id), any(GeofenceZoneSpec.class)))
                .thenReturn(zone(id, "Renamed", ZoneKind.KEEP_IN, 100.0, false));

        String body = """
                {
                  "name": "Renamed",
                  "kind": "keep_in",
                  "polygon": [
                    {"latitude": 10.0, "longitude": 20.0},
                    {"latitude": 10.0, "longitude": 21.0},
                    {"latitude": 11.0, "longitude": 20.5}
                  ],
                  "maxAltitudeMeters": 100.0,
                  "enabled": false
                }
                """;

        mockMvc.perform(put("/api/geofences/{id}", id.value()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.kind").value("KEEP_IN"))
                .andExpect(jsonPath("$.maxAltitudeMeters").value(100.0))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void updateReturns404ForUnknownZoneId() throws Exception {
        ZoneId id = ZoneId.random();
        when(geofenceService.update(eq(id), any(GeofenceZoneSpec.class)))
                .thenThrow(new NoSuchElementException("No geofence zone with id " + id));

        String body = """
                {
                  "name": "Renamed",
                  "kind": "keep_in",
                  "polygon": [
                    {"latitude": 10.0, "longitude": 20.0},
                    {"latitude": 10.0, "longitude": 21.0},
                    {"latitude": 11.0, "longitude": 20.5}
                  ],
                  "enabled": true
                }
                """;

        mockMvc.perform(put("/api/geofences/{id}", id.value()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateReturns400ForAMalformedZoneId() throws Exception {
        String body = """
                {
                  "name": "Renamed",
                  "kind": "keep_in",
                  "polygon": [
                    {"latitude": 10.0, "longitude": 20.0},
                    {"latitude": 10.0, "longitude": 21.0},
                    {"latitude": 11.0, "longitude": 20.5}
                  ],
                  "enabled": true
                }
                """;

        mockMvc.perform(put("/api/geofences/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void deleteReturns204() throws Exception {
        ZoneId id = ZoneId.random();

        mockMvc.perform(delete("/api/geofences/{id}", id.value())).andExpect(status().isNoContent());

        verify(geofenceService).delete(id);
    }

    @Test
    void deleteReturns404ForUnknownZoneId() throws Exception {
        ZoneId id = ZoneId.random();
        doThrow(new NoSuchElementException("No geofence zone with id " + id)).when(geofenceService).delete(id);

        mockMvc.perform(delete("/api/geofences/{id}", id.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void deleteReturns400ForAMalformedZoneId() throws Exception {
        mockMvc.perform(delete("/api/geofences/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- docs/plans/done/LIVE-SCOPE-PLAN.md §2.2, W5: authority --------------------------------
    //
    // Every test above runs under the class-level `currentUser` (unbounded scope, matching how a
    // deployment with `vision.auth.enabled=false` behaves today) and is untouched by this wave --
    // that is the "default-config suites stay green" bar. The tests below prove the write gate: a
    // geofence zone is global no-fly-zone data with no owning asset/group (GeofenceService's own
    // javadoc), so `canAdminister()` gates every write uniformly -- a MANAGER's `canManageOrg()`
    // authority over their own group's assets does not extend to a boundary every group's aircraft
    // must obey. `list` stays open to every scope, proved by the identical body the class-level
    // (unbounded) tests above already exercise.

    private static final String CREATE_BODY = """
            {
              "name": "New zone",
              "kind": "keep_out",
              "polygon": [
                {"latitude": 10.0, "longitude": 20.0},
                {"latitude": 10.0, "longitude": 21.0},
                {"latitude": 11.0, "longitude": 20.5}
              ],
              "enabled": true
            }
            """;

    /**
     * A {@link CurrentUser} answering with a caller-supplied {@link VisibilityScope} — the same
     * idiom {@code StreamControllerTest#currentUserWithScope} uses. {@link PrincipalResolver#viewer()}
     * is never called by {@link GeofenceController}, so it throws rather than fake a map viewer no
     * test here needs.
     */
    private CurrentUser currentUserWithScope(VisibilityScope scope) {
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return UserId.random();
            }

            @Override
            public Ownership ownership() {
                return new Ownership(UserId.random(), GroupId.random());
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("GeofenceController never calls viewer()");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("GeofenceController never calls role()");
            }

            @Override
            public Authority authority() {
                return new Authority(scope, EnumSet.allOf(Capability.class));
            }
        });
    }

    private MockMvc pilotMvc() {
        return mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));
    }

    private MockMvc managerMvc() {
        return mockMvcFor(currentUserWithScope(VisibilityScope.groups(Set.of(GroupId.random()))));
    }

    private MockMvc adminMvc() {
        return mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()));
    }

    @Test
    void createReturns403ForAPilotScope() throws Exception {
        pilotMvc().perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(geofenceService);
    }

    @Test
    void createReturns403ForAManagerScope() throws Exception {
        // A no-fly zone binds every group's aircraft, not just the manager's own -- canManageOrg()
        // (true for a manager) is deliberately not enough; only canAdminister() is.
        managerMvc().perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(geofenceService);
    }

    @Test
    void createSucceedsForAnAdminScope() throws Exception {
        ZoneId created = ZoneId.random();
        when(geofenceService.create(any(GeofenceZoneSpec.class)))
                .thenReturn(zone(created, "New zone", ZoneKind.KEEP_OUT, null, true));

        adminMvc().perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());

        verify(geofenceService).create(any(GeofenceZoneSpec.class));
    }

    @Test
    void updateReturns403ForAPilotScope() throws Exception {
        ZoneId id = ZoneId.random();

        pilotMvc().perform(put("/api/geofences/{id}", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(geofenceService, never()).update(any(), any());
    }

    @Test
    void updateReturns403ForAManagerScope() throws Exception {
        ZoneId id = ZoneId.random();

        managerMvc().perform(put("/api/geofences/{id}", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(geofenceService, never()).update(any(), any());
    }

    @Test
    void deleteReturns403ForAPilotScope() throws Exception {
        ZoneId id = ZoneId.random();

        pilotMvc().perform(delete("/api/geofences/{id}", id.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(geofenceService, never()).delete(any());
    }

    @Test
    void deleteReturns403ForAManagerScope() throws Exception {
        ZoneId id = ZoneId.random();

        managerMvc().perform(delete("/api/geofences/{id}", id.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(geofenceService, never()).delete(any());
    }

    @Test
    void listIsUnfilteredForAPilotScope() throws Exception {
        // Zones carry no asset/group -- every scope sees every zone (see class javadoc's
        // `@OpenByDesign` reasoning).
        when(geofenceService.zones()).thenReturn(List.of(
                zone(ZoneId.random(), "Airport keep-out", ZoneKind.KEEP_OUT, 50.0, true)));

        pilotMvc().perform(get("/api/geofences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
