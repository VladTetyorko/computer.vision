package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.map.DrawingPatch;
import com.drones.vision.application.map.DrawingService;
import com.drones.vision.application.map.DrawingSpec;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.domain.model.DrawKind;
import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.DrawingId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link MapDrawingsController} (docs/plans/done/MAP-REWORK-PLAN.md §4.1/§4.2's frozen wire
 * contract).
 */
class MapDrawingsControllerTest {

    private DrawingService drawings;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        drawings = mock(DrawingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MapDrawingsController(drawings, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Drawing drawing(DrawingId id, LayerId layerId, DrawKind kind, List<GeoPosition> points,
                             String label, String colorToken) {
        return new Drawing(id, layerId, kind, points, label, colorToken, ownership,
                Instant.parse("2026-08-05T10:00:00Z"));
    }

    private static List<GeoPosition> line() {
        return List.of(new GeoPosition(50.0, 30.0, null), new GeoPosition(50.5, 30.5, null));
    }

    // ---- GET /api/map/drawings ----

    @Test
    void listReturns200WithMappedDrawings() throws Exception {
        DrawingId id = DrawingId.random();
        LayerId layerId = LayerId.random();
        when(drawings.list(any())).thenReturn(List.of(
                drawing(id, layerId, DrawKind.POLYGON,
                        List.of(new GeoPosition(50.0, 30.0, null), new GeoPosition(50.1, 30.0, null),
                                new GeoPosition(50.1, 30.1, 120.0)),
                        "Assembly area", "accent")));

        mockMvc.perform(get("/api/map/drawings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].drawingId").value(id.value().toString()))
                .andExpect(jsonPath("$[0].layerId").value(layerId.value().toString()))
                .andExpect(jsonPath("$[0].kind").value("POLYGON"))
                .andExpect(jsonPath("$[0].label").value("Assembly area"))
                .andExpect(jsonPath("$[0].colorToken").value("accent"))
                .andExpect(jsonPath("$[0].points", hasSize(3)))
                .andExpect(jsonPath("$[0].points[0].latitude").value(50.0))
                .andExpect(jsonPath("$[0].points[2].altitudeMeters").value(120.0))
                .andExpect(jsonPath("$[0].createdByUserId").value(ownerId.value().toString()));
    }

    @Test
    void absentLabelAndColorTokenAreOmittedFromTheJson() throws Exception {
        when(drawings.list(any())).thenReturn(List.of(
                drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, line(), null, null)));

        mockMvc.perform(get("/api/map/drawings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").doesNotExist())
                .andExpect(jsonPath("$[0].colorToken").doesNotExist())
                .andExpect(jsonPath("$[0].points[0].altitudeMeters").doesNotExist());
    }

    @Test
    void aDrawingOnAnInvisibleLayerIsSimplyAbsent() throws Exception {
        when(drawings.list(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/map/drawings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- POST /api/map/drawings ----

    @Test
    void createReturns201AndThreadsTheParsedSpecThrough() throws Exception {
        DrawingId created = DrawingId.random();
        LayerId layerId = LayerId.random();
        when(drawings.create(any(), any()))
                .thenReturn(drawing(created, layerId, DrawKind.LINE, line(), "Route", "accent"));

        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layerId\":\"" + layerId.value() + "\",\"kind\":\"line\","
                                + "\"label\":\"Route\",\"colorToken\":\"accent\",\"points\":["
                                + "{\"latitude\":50.0,\"longitude\":30.0},"
                                + "{\"latitude\":50.5,\"longitude\":30.5}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.drawingId").value(created.value().toString()));

        ArgumentCaptor<DrawingSpec> spec = ArgumentCaptor.forClass(DrawingSpec.class);
        verify(drawings).create(any(), spec.capture());
        assertEquals(layerId, spec.getValue().layerId());
        assertEquals(DrawKind.LINE, spec.getValue().kind(), "kind parses case-insensitively");
        assertEquals(line(), spec.getValue().points());
        assertEquals("Route", spec.getValue().label());
        assertEquals("accent", spec.getValue().colorToken());
    }

    @Test
    void createWithNoLayerIdLetsTheServiceResolveTheDefaultLayer() throws Exception {
        when(drawings.create(any(), any()))
                .thenReturn(drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, line(), null, null));

        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LINE\",\"points\":[{\"latitude\":50.0,\"longitude\":30.0},"
                                + "{\"latitude\":50.5,\"longitude\":30.5}]}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<DrawingSpec> spec = ArgumentCaptor.forClass(DrawingSpec.class);
        verify(drawings).create(any(), spec.capture());
        assertNull(spec.getValue().layerId());
    }

    @Test
    void createReturns400WhenAPolygonHasTooFewPoints() throws Exception {
        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"POLYGON\",\"points\":[{\"latitude\":50.0,\"longitude\":30.0},"
                                + "{\"latitude\":50.5,\"longitude\":30.5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void createReturns400WhenATextDrawingHasNoLabel() throws Exception {
        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"points\":[{\"latitude\":50.0,\"longitude\":30.0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns400WhenPointsAreAbsentEntirely() throws Exception {
        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LINE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns400ForAnUnrecognizedKind() throws Exception {
        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CIRCLE\",\"points\":[{\"latitude\":50.0,\"longitude\":30.0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns403WhenTheCallerMayNotContributeToTheLayer() throws Exception {
        when(drawings.create(any(), any())).thenThrow(new AccessDeniedException("not permitted to contribute"));

        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layerId\":\"" + LayerId.random().value() + "\",\"kind\":\"LINE\","
                                + "\"points\":[{\"latitude\":50.0,\"longitude\":30.0},"
                                + "{\"latitude\":50.5,\"longitude\":30.5}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReturns404WhenTheNamedLayerDoesNotExist() throws Exception {
        when(drawings.create(any(), any())).thenThrow(new NoSuchElementException("Unknown map layer"));

        mockMvc.perform(post("/api/map/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layerId\":\"" + LayerId.random().value() + "\",\"kind\":\"LINE\","
                                + "\"points\":[{\"latitude\":50.0,\"longitude\":30.0},"
                                + "{\"latitude\":50.5,\"longitude\":30.5}]}"))
                .andExpect(status().isNotFound());
    }

    // ---- PATCH /api/map/drawings/{id} ----

    @Test
    void patchReplacesGeometryWholesaleWhenPointsArePresent() throws Exception {
        DrawingId id = DrawingId.random();
        when(drawings.patch(any(), eq(id), any()))
                .thenReturn(drawing(id, LayerId.random(), DrawKind.LINE, line(), "Moved", null));

        mockMvc.perform(patch("/api/map/drawings/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":[{\"latitude\":50.0,\"longitude\":30.0},"
                                + "{\"latitude\":50.5,\"longitude\":30.5}],\"label\":\"Moved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Moved"));

        ArgumentCaptor<DrawingPatch> patch = ArgumentCaptor.forClass(DrawingPatch.class);
        verify(drawings).patch(any(), eq(id), patch.capture());
        assertEquals(Optional.of(line()), patch.getValue().points());
        assertEquals(Optional.of("Moved"), patch.getValue().label());
        assertEquals(Optional.empty(), patch.getValue().colorToken(), "absent means unchanged");
    }

    @Test
    void patchLeavesGeometryAloneWhenPointsAreAbsent() throws Exception {
        DrawingId id = DrawingId.random();
        when(drawings.patch(any(), eq(id), any()))
                .thenReturn(drawing(id, LayerId.random(), DrawKind.LINE, line(), null, "danger"));

        mockMvc.perform(patch("/api/map/drawings/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"colorToken\":\"danger\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DrawingPatch> patch = ArgumentCaptor.forClass(DrawingPatch.class);
        verify(drawings).patch(any(), eq(id), patch.capture());
        assertEquals(Optional.empty(), patch.getValue().points());
        assertEquals(Optional.of("danger"), patch.getValue().colorToken());
    }

    @Test
    void patchReturns404ForAnUnknownDrawing() throws Exception {
        DrawingId id = DrawingId.random();
        when(drawings.patch(any(), eq(id), any())).thenThrow(new NoSuchElementException("Unknown drawing"));

        mockMvc.perform(patch("/api/map/drawings/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nope\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchReturns403WhenTheCallerIsNeitherCreatorNorManager() throws Exception {
        DrawingId id = DrawingId.random();
        when(drawings.patch(any(), eq(id), any())).thenThrow(new AccessDeniedException("not permitted"));

        mockMvc.perform(patch("/api/map/drawings/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchReturns400ForAMalformedDrawingId() throws Exception {
        mockMvc.perform(patch("/api/map/drawings/not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nope\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- DELETE /api/map/drawings/{id} ----

    @Test
    void deleteReturns204() throws Exception {
        DrawingId id = DrawingId.random();

        mockMvc.perform(delete("/api/map/drawings/" + id.value()))
                .andExpect(status().isNoContent());

        verify(drawings).delete(any(), eq(id));
    }

    @Test
    void deleteReturns403WhenTheCallerIsNeitherCreatorNorManager() throws Exception {
        DrawingId id = DrawingId.random();
        doThrow(new AccessDeniedException("not permitted")).when(drawings).delete(any(), eq(id));

        mockMvc.perform(delete("/api/map/drawings/" + id.value()))
                .andExpect(status().isForbidden());
    }
}
