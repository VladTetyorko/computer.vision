package com.drones.vision.api;

import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.application.AccessDeniedException;
import com.drones.vision.application.GeolocateSpec;
import com.drones.vision.application.MarkPatch;
import com.drones.vision.application.MarkService;
import com.drones.vision.application.MarkSpec;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GeoProjection;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link MarksController} (docs/TACTICAL-MARKS-PLAN.md §4/M4's frozen wire
 * contract), mirroring {@link GeofenceControllerTest}/{@link AssetControllerTest}'s style: a
 * standalone {@code MockMvc} over a mocked {@link MarkService}, with {@link ApiExceptionHandler}
 * attached so error mapping is exercised exactly as it runs in production.
 */
class MarksControllerTest {

    private MarkService markService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        markService = mock(MarkService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MarksController(markService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Mark mark(MarkId id, MarkKind kind, String label, MarkStatus status, MarkSource source) {
        return new Mark(id, new GeoPosition(50.45, 30.52, null), kind, label, null, ownership, Instant.now(),
                status, source);
    }

    // ---- GET /api/marks ----

    @Test
    void listReturns200WithMappedActiveMarksInServiceOrder() throws Exception {
        MarkId first = MarkId.random();
        MarkId second = MarkId.random();
        when(markService.list()).thenReturn(List.of(
                mark(first, MarkKind.TARGET, "Bunker", MarkStatus.ACTIVE, MarkSource.MANUAL),
                mark(second, MarkKind.HAZARD, "Wire", MarkStatus.ACTIVE, MarkSource.DETECTION)));

        mockMvc.perform(get("/api/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.value().toString()))
                .andExpect(jsonPath("$[0].kind").value("TARGET"))
                .andExpect(jsonPath("$[0].label").value("Bunker"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].source").value("MANUAL"))
                .andExpect(jsonPath("$[0].position.latitude").value(50.45))
                .andExpect(jsonPath("$[0].createdBy").value(ownerId.value().toString()))
                .andExpect(jsonPath("$[1].kind").value("HAZARD"))
                .andExpect(jsonPath("$[1].source").value("DETECTION"));
    }

    @Test
    void listReturns200WithEmptyListWhenNoMarks() throws Exception {
        when(markService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- POST /api/marks ----

    @Test
    void createReturns201AndThreadsCurrentUsersOwnershipAndActor() throws Exception {
        MarkId created = MarkId.random();
        when(markService.create(any(MarkSpec.class), eq(ownership), eq(ownerId)))
                .thenReturn(mark(created, MarkKind.POI, "Waypoint", MarkStatus.ACTIVE, MarkSource.MANUAL));

        String body = """
                {
                  "kind": "poi",
                  "label": "Waypoint",
                  "position": {"latitude": 50.45, "longitude": 30.52}
                }
                """;

        mockMvc.perform(post("/api/marks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.value().toString()))
                .andExpect(jsonPath("$.kind").value("POI"))
                .andExpect(jsonPath("$.label").value("Waypoint"))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        ArgumentCaptor<MarkSpec> captor = ArgumentCaptor.forClass(MarkSpec.class);
        verify(markService).create(captor.capture(), eq(ownership), eq(ownerId));
        assertEquals(MarkKind.POI, captor.getValue().kind());
        assertEquals(50.45, captor.getValue().position().latitude());
    }

    @Test
    void createReturns400ForAnUnrecognizedKind() throws Exception {
        String body = """
                {
                  "kind": "sideways",
                  "label": "Bad kind",
                  "position": {"latitude": 50.45, "longitude": 30.52}
                }
                """;

        mockMvc.perform(post("/api/marks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- POST /api/marks/geolocate ----

    @Test
    void geolocateReturns201AndDefaultsDepressionAndLabelWhenAbsent() throws Exception {
        MarkId created = MarkId.random();
        when(markService.geolocate(any(GeolocateSpec.class), eq(ownership), eq(ownerId)))
                .thenReturn(mark(created, MarkKind.TARGET, "Contact", MarkStatus.ACTIVE, MarkSource.DETECTION));

        String body = """
                {"assetId": "%s"}
                """.formatted(AssetId.random().value());

        mockMvc.perform(post("/api/marks/geolocate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("DETECTION"));

        ArgumentCaptor<GeolocateSpec> captor = ArgumentCaptor.forClass(GeolocateSpec.class);
        verify(markService).geolocate(captor.capture(), eq(ownership), eq(ownerId));
        assertEquals(MarkKind.TARGET, captor.getValue().kind(), "kind absent must default to TARGET (\"Mark target\")");
        assertEquals("Contact", captor.getValue().label(), "label absent must default to \"Contact\"");
        assertEquals(GeoProjection.DEFAULT_DEPRESSION_DEGREES, captor.getValue().depressionDegrees(),
                "depressionDegrees absent must default to GeoProjection.DEFAULT_DEPRESSION_DEGREES");
    }

    @Test
    void geolocateReturns201AndHonorsExplicitFieldsWhenPresent() throws Exception {
        MarkId created = MarkId.random();
        when(markService.geolocate(any(GeolocateSpec.class), eq(ownership), eq(ownerId)))
                .thenReturn(mark(created, MarkKind.HAZARD, "Wire", MarkStatus.ACTIVE, MarkSource.DETECTION));

        String body = """
                {"assetId": "%s", "kind": "hazard", "label": "Wire", "depressionDegrees": 30.0}
                """.formatted(AssetId.random().value());

        mockMvc.perform(post("/api/marks/geolocate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<GeolocateSpec> captor = ArgumentCaptor.forClass(GeolocateSpec.class);
        verify(markService).geolocate(captor.capture(), eq(ownership), eq(ownerId));
        assertEquals(MarkKind.HAZARD, captor.getValue().kind());
        assertEquals("Wire", captor.getValue().label());
        assertEquals(30.0, captor.getValue().depressionDegrees());
    }

    @Test
    void geolocateReturns400WhenTelemetryIsIncomplete() throws Exception {
        when(markService.geolocate(any(GeolocateSpec.class), eq(ownership), eq(ownerId)))
                .thenThrow(new IllegalArgumentException("cannot geolocate: telemetry incomplete"));

        String body = """
                {"assetId": "%s"}
                """.formatted(AssetId.random().value());

        mockMvc.perform(post("/api/marks/geolocate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("cannot geolocate: telemetry incomplete"));
    }

    @Test
    void geolocateReturns400ForABlankAssetId() throws Exception {
        mockMvc.perform(post("/api/marks/geolocate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- PATCH /api/marks/{id} ----

    @Test
    void updateReturns200WithUpdatedMark() throws Exception {
        MarkId id = MarkId.random();
        when(markService.update(eq(id), any(MarkPatch.class), eq(ownerId), any()))
                .thenReturn(mark(id, MarkKind.FRIENDLY, "Renamed", MarkStatus.ACTIVE, MarkSource.MANUAL));

        String body = """
                {"label": "Renamed", "kind": "friendly"}
                """;

        mockMvc.perform(patch("/api/marks/{id}", id.value()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Renamed"))
                .andExpect(jsonPath("$.kind").value("FRIENDLY"));

        ArgumentCaptor<MarkPatch> captor = ArgumentCaptor.forClass(MarkPatch.class);
        verify(markService).update(eq(id), captor.capture(), eq(ownerId), any());
        assertEquals("Renamed", captor.getValue().label().orElse(null));
        assertEquals(MarkKind.FRIENDLY, captor.getValue().kind().orElse(null));
        assertEquals(java.util.Optional.empty(), captor.getValue().status(), "absent status must not change anything");
    }

    @Test
    void updateReturns403WhenActorIsNeitherCreatorNorManager() throws Exception {
        MarkId id = MarkId.random();
        when(markService.update(eq(id), any(MarkPatch.class), eq(ownerId), any()))
                .thenThrow(new AccessDeniedException("Mark " + id.value() + " may only be edited or deleted by its creator or a manager"));

        mockMvc.perform(patch("/api/marks/{id}", id.value()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"cleared\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void updateReturns404ForUnknownMarkId() throws Exception {
        MarkId id = MarkId.random();
        when(markService.update(eq(id), any(MarkPatch.class), eq(ownerId), any()))
                .thenThrow(new NoSuchElementException("Unknown mark: " + id.value()));

        mockMvc.perform(patch("/api/marks/{id}", id.value()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateReturns400ForAMalformedMarkId() throws Exception {
        mockMvc.perform(patch("/api/marks/{id}", "not-a-uuid").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void updateWithAPresentBlankNoteIsPassedThroughPresentAndOtherAbsentFieldsStayEmpty() throws Exception {
        MarkId id = MarkId.random();
        when(markService.update(eq(id), any(MarkPatch.class), eq(ownerId), any()))
                .thenReturn(mark(id, MarkKind.TARGET, "Bunker", MarkStatus.ACTIVE, MarkSource.MANUAL));

        mockMvc.perform(patch("/api/marks/{id}", id.value()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<MarkPatch> captor = ArgumentCaptor.forClass(MarkPatch.class);
        verify(markService).update(eq(id), captor.capture(), eq(ownerId), any());
        MarkPatch patch = captor.getValue();
        assertEquals("", patch.note().orElse(null),
                "a present blank note stays present at the patch layer -- Mark#withDetails normalizes it to null");
        assertEquals(java.util.Optional.empty(), patch.label(), "an absent field must stay Optional.empty()");
        assertEquals(java.util.Optional.empty(), patch.kind(), "an absent field must stay Optional.empty()");
        assertEquals(java.util.Optional.empty(), patch.position(), "an absent field must stay Optional.empty()");
        assertEquals(java.util.Optional.empty(), patch.status(), "an absent field must stay Optional.empty()");
    }

    // ---- DELETE /api/marks/{id} ----

    @Test
    void deleteReturns204() throws Exception {
        MarkId id = MarkId.random();

        mockMvc.perform(delete("/api/marks/{id}", id.value())).andExpect(status().isNoContent());

        verify(markService).delete(id, ownerId, currentUser.scope());
    }

    @Test
    void deleteReturns403WhenActorIsNeitherCreatorNorManager() throws Exception {
        MarkId id = MarkId.random();
        org.mockito.Mockito.doThrow(new AccessDeniedException(
                        "Mark " + id.value() + " may only be edited or deleted by its creator or a manager"))
                .when(markService).delete(eq(id), eq(ownerId), any());

        mockMvc.perform(delete("/api/marks/{id}", id.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void deleteReturns404ForUnknownMarkId() throws Exception {
        MarkId id = MarkId.random();
        org.mockito.Mockito.doThrow(new NoSuchElementException("Unknown mark: " + id.value()))
                .when(markService).delete(eq(id), eq(ownerId), any());

        mockMvc.perform(delete("/api/marks/{id}", id.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void deleteReturns400ForAMalformedMarkId() throws Exception {
        mockMvc.perform(delete("/api/marks/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
