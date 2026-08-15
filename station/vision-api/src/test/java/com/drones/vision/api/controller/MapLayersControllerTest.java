package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.map.application.DrawingService;
import com.drones.vision.map.application.LayerSpec;
import com.drones.vision.map.application.LayerView;
import com.drones.vision.map.application.MapLayerService;
import com.drones.vision.map.application.mark.MarkService;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.map.domain.model.DrawKind;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerGrant;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link MapLayersController} (docs/plans/done/MAP-REWORK-PLAN.md §4.1's frozen wire
 * contract), in the style of {@code GeofenceControllerTest}: a standalone {@code MockMvc} over
 * mocked services, with {@link ApiExceptionHandler} attached so error mapping runs exactly as it
 * does in production.
 */
class MapLayersControllerTest {

    private MapLayerService layers;
    private MarkService marks;
    private DrawingService drawings;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final GroupId groupId = GroupId.random();
    private final Ownership ownership = new Ownership(ownerId, groupId);
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        layers = mock(MapLayerService.class);
        marks = mock(MarkService.class);
        drawings = mock(DrawingService.class);
        when(marks.list(any())).thenReturn(List.of());
        when(drawings.list(any())).thenReturn(List.of());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MapLayersController(layers, marks, drawings, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MapLayer layer(LayerId id, String name, LayerKind kind, List<LayerGrant> grants) {
        return new MapLayer(id, name, kind, ownership, grants, Instant.parse("2026-08-05T10:00:00Z"));
    }

    private Mark markOn(LayerId layerId) {
        return new Mark(MarkId.random(), layerId, new GeoPosition(50.45, 30.52, null), MarkKind.TARGET,
                Affiliation.HOSTILE, "Bunker", null, ownership, Instant.now(), MarkStatus.ACTIVE,
                MarkSource.MANUAL, Verification.unverified());
    }

    private Drawing drawingOn(LayerId layerId) {
        return new Drawing(DrawingId.random(), layerId, DrawKind.LINE,
                List.of(new GeoPosition(50.0, 30.0, null), new GeoPosition(50.5, 30.5, null)),
                null, null, ownership, Instant.now());
    }

    // ---- GET /api/map/layers ----

    @Test
    void listReturns200WithVisibleLayersInServiceOrder() throws Exception {
        LayerId cop = LayerId.random();
        LayerId team = LayerId.random();
        when(layers.layers(any())).thenReturn(List.of(
                new LayerView(layer(cop, "Common picture", LayerKind.COP, List.of()), AccessLevel.VIEW),
                new LayerView(layer(team, "Bravo team", LayerKind.TEAM, List.of()), AccessLevel.CONTRIBUTE)));

        mockMvc.perform(get("/api/map/layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].layerId").value(cop.value().toString()))
                .andExpect(jsonPath("$[0].kind").value("COP"))
                .andExpect(jsonPath("$[0].myAccess").value("VIEW"))
                .andExpect(jsonPath("$[0].ownerUserId").value(ownerId.value().toString()))
                .andExpect(jsonPath("$[0].groupId").value(groupId.value().toString()))
                .andExpect(jsonPath("$[1].layerId").value(team.value().toString()))
                .andExpect(jsonPath("$[1].myAccess").value("CONTRIBUTE"));
    }

    @Test
    void listCountsOnlyTheMarksAndDrawingsSittingOnEachLayer() throws Exception {
        LayerId first = LayerId.random();
        LayerId second = LayerId.random();
        when(layers.layers(any())).thenReturn(List.of(
                new LayerView(layer(first, "First", LayerKind.TEAM, List.of()), AccessLevel.CONTRIBUTE),
                new LayerView(layer(second, "Second", LayerKind.TEAM, List.of()), AccessLevel.VIEW)));
        when(marks.list(any())).thenReturn(List.of(markOn(first), markOn(first), markOn(second)));
        when(drawings.list(any())).thenReturn(List.of(drawingOn(second)));

        mockMvc.perform(get("/api/map/layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].markCount").value(2))
                .andExpect(jsonPath("$[0].drawingCount").value(0))
                .andExpect(jsonPath("$[1].markCount").value(1))
                .andExpect(jsonPath("$[1].drawingCount").value(1));
    }

    @Test
    void grantsAreOmittedUnlessTheCallerManagesTheLayer() throws Exception {
        LayerGrant grant = new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), AccessLevel.VIEW);
        when(layers.layers(any())).thenReturn(List.of(
                new LayerView(layer(LayerId.random(), "Managed", LayerKind.TEAM, List.of(grant)),
                        AccessLevel.MANAGE),
                new LayerView(layer(LayerId.random(), "Contributed", LayerKind.TEAM, List.of(grant)),
                        AccessLevel.CONTRIBUTE)));

        mockMvc.perform(get("/api/map/layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grants", hasSize(1)))
                .andExpect(jsonPath("$[0].grants[0].subjectType").value("USER"))
                .andExpect(jsonPath("$[0].grants[0].level").value("VIEW"))
                // NON_NULL: a non-manager never learns who else can see the layer
                .andExpect(jsonPath("$[1].grants").doesNotExist());
    }

    @Test
    void aLayerBelongingToAnotherTeamIsSimplyAbsentFromTheList() throws Exception {
        LayerId mine = LayerId.random();
        // The service filters to canView layers; a pilot's read of another team's layer never
        // reaches the wire at all -- reads hide, they do not 403.
        when(layers.layers(any())).thenReturn(List.of(
                new LayerView(layer(mine, "Mine", LayerKind.TEAM, List.of()), AccessLevel.CONTRIBUTE)));

        mockMvc.perform(get("/api/map/layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].layerId").value(mine.value().toString()));
    }

    // ---- POST /api/map/layers ----

    @Test
    void createReturns201AndThreadsTheParsedSpecThrough() throws Exception {
        LayerId created = LayerId.random();
        GroupId team = GroupId.random();
        when(layers.create(any(), any())).thenReturn(layer(created, "Bravo team", LayerKind.TEAM, List.of()));

        mockMvc.perform(post("/api/map/layers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bravo team\",\"kind\":\"team\",\"groupId\":\""
                                + team.value() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.layerId").value(created.value().toString()))
                .andExpect(jsonPath("$.myAccess").value("MANAGE"))
                .andExpect(jsonPath("$.markCount").value(0))
                .andExpect(jsonPath("$.drawingCount").value(0));

        ArgumentCaptor<LayerSpec> spec = ArgumentCaptor.forClass(LayerSpec.class);
        verify(layers).create(any(), spec.capture());
        assertEquals("Bravo team", spec.getValue().name());
        assertEquals(LayerKind.TEAM, spec.getValue().kind(), "kind parses case-insensitively");
        assertEquals(team, spec.getValue().groupId());
    }

    @Test
    void createReturns400ForAnUnrecognizedKind() throws Exception {
        mockMvc.perform(post("/api/map/layers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"kind\":\"SQUAD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void createReturns400WhenATeamLayerCarriesNoGroup() throws Exception {
        mockMvc.perform(post("/api/map/layers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bravo\",\"kind\":\"TEAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns403WhenTheCallerMayNotCreateALayerForThatGroup() throws Exception {
        when(layers.create(any(), any())).thenThrow(new AccessDeniedException("not permitted"));

        mockMvc.perform(post("/api/map/layers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bravo\",\"kind\":\"TEAM\",\"groupId\":\""
                                + GroupId.random().value() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ---- PATCH /api/map/layers/{id} ----

    @Test
    void renameReturns200WithTheRenamedLayer() throws Exception {
        LayerId id = LayerId.random();
        when(layers.rename(any(), eq(id), eq("Charlie team")))
                .thenReturn(layer(id, "Charlie team", LayerKind.TEAM, List.of()));

        mockMvc.perform(patch("/api/map/layers/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Charlie team\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Charlie team"));
    }

    @Test
    void renameReturns404ForAnUnknownOrInvisibleLayer() throws Exception {
        LayerId id = LayerId.random();
        when(layers.rename(any(), eq(id), any())).thenThrow(new NoSuchElementException("Unknown map layer"));

        mockMvc.perform(patch("/api/map/layers/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void renameReturns409ForTheCopLayer() throws Exception {
        LayerId id = LayerId.random();
        when(layers.rename(any(), eq(id), any()))
                .thenThrow(new IllegalStateException("The COP layer cannot be renamed"));

        mockMvc.perform(patch("/api/map/layers/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void renameReturns400ForAMalformedLayerId() throws Exception {
        mockMvc.perform(patch("/api/map/layers/not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- DELETE /api/map/layers/{id} ----

    @Test
    void deleteReturns204() throws Exception {
        LayerId id = LayerId.random();

        mockMvc.perform(delete("/api/map/layers/" + id.value()))
                .andExpect(status().isNoContent());

        verify(layers).delete(any(), eq(id));
    }

    @Test
    void deleteReturns403WhenTheCallerDoesNotManageTheLayer() throws Exception {
        LayerId id = LayerId.random();
        org.mockito.Mockito.doThrow(new AccessDeniedException("not permitted to manage layer"))
                .when(layers).delete(any(), eq(id));

        mockMvc.perform(delete("/api/map/layers/" + id.value()))
                .andExpect(status().isForbidden());
    }

    // ---- PUT /api/map/layers/{id}/grants ----

    @Test
    void setGrantsReplacesTheListWholesaleAndReturnsIt() throws Exception {
        LayerId id = LayerId.random();
        UUID subject = UUID.randomUUID();
        LayerGrant granted = new LayerGrant(LayerGrant.SubjectType.GROUP, subject, AccessLevel.CONTRIBUTE);
        when(layers.setGrants(any(), eq(id), any()))
                .thenReturn(layer(id, "Bravo team", LayerKind.TEAM, List.of(granted)));

        mockMvc.perform(put("/api/map/layers/" + id.value() + "/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[{\"subjectType\":\"GROUP\",\"subjectId\":\"" + subject
                                + "\",\"level\":\"CONTRIBUTE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants", hasSize(1)))
                .andExpect(jsonPath("$.grants[0].subjectId").value(subject.toString()))
                .andExpect(jsonPath("$.grants[0].level").value("CONTRIBUTE"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LayerGrant>> captured = ArgumentCaptor.forClass(List.class);
        verify(layers).setGrants(any(), eq(id), captured.capture());
        assertEquals(List.of(granted), captured.getValue());
    }

    @Test
    void setGrantsWithAnEmptyListClearsEveryGrant() throws Exception {
        LayerId id = LayerId.random();
        when(layers.setGrants(any(), eq(id), any()))
                .thenReturn(layer(id, "Bravo team", LayerKind.TEAM, List.of()));

        mockMvc.perform(put("/api/map/layers/" + id.value() + "/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants", hasSize(0)));

        verify(layers).setGrants(any(), eq(id), eq(List.of()));
    }

    @Test
    void setGrantsReturns400ForAnUnrecognizedLevel() throws Exception {
        LayerId id = LayerId.random();

        mockMvc.perform(put("/api/map/layers/" + id.value() + "/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[{\"subjectType\":\"USER\",\"subjectId\":\""
                                + UUID.randomUUID() + "\",\"level\":\"OWNER\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setGrantsReturns403WhenTheCallerDoesNotManageTheLayer() throws Exception {
        LayerId id = LayerId.random();
        when(layers.setGrants(any(), eq(id), any()))
                .thenThrow(new AccessDeniedException("not permitted to manage layer"));

        mockMvc.perform(put("/api/map/layers/" + id.value() + "/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[]}"))
                .andExpect(status().isForbidden());
    }
}
