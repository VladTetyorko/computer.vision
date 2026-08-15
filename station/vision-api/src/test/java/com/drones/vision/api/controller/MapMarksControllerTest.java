package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.application.mark.GeolocateSpec;
import com.drones.vision.map.application.mark.GeolocationResult;
import com.drones.vision.map.application.mark.MarkPatch;
import com.drones.vision.map.application.mark.MarkService;
import com.drones.vision.map.application.mark.MarkSpec;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.map.domain.model.Verification.VerificationState;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * MockMvc tests for {@link MapMarksController} (docs/plans/done/MAP-REWORK-PLAN.md §4.1/§4.2's frozen wire
 * contract) — the replacement for the deleted {@code MarksControllerTest}, extended with the
 * verify/promote endpoints and the affiliation/layer/verification fields the rework adds.
 */
class MapMarksControllerTest {

    private MarkService marks;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final GroupId groupId = GroupId.random();
    private final Ownership ownership = new Ownership(ownerId, groupId);
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        marks = mock(MarkService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MapMarksController(marks, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Mark mark(MarkId id, LayerId layerId, MarkKind kind, Affiliation affiliation, MarkStatus status,
                       Verification verification) {
        return new Mark(id, layerId, new GeoPosition(50.45, 30.52, 120.0), kind, affiliation, "Bunker",
                "Two entrances", ownership, Instant.parse("2026-08-05T10:00:00Z"), status, MarkSource.MANUAL,
                verification);
    }

    // ---- GET /api/map/marks ----

    @Test
    void listReturns200WithMappedMarksInServiceOrder() throws Exception {
        MarkId first = MarkId.random();
        MarkId second = MarkId.random();
        LayerId layerId = LayerId.random();
        when(marks.list(any())).thenReturn(List.of(
                mark(first, layerId, MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        Verification.unverified()),
                mark(second, layerId, MarkKind.UNIT, Affiliation.FRIENDLY, MarkStatus.ACTIVE,
                        Verification.unverified())));

        mockMvc.perform(get("/api/map/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].markId").value(first.value().toString()))
                .andExpect(jsonPath("$[0].layerId").value(layerId.value().toString()))
                .andExpect(jsonPath("$[0].kind").value("TARGET"))
                .andExpect(jsonPath("$[0].affiliation").value("HOSTILE"))
                .andExpect(jsonPath("$[0].latitude").value(50.45))
                .andExpect(jsonPath("$[0].longitude").value(30.52))
                .andExpect(jsonPath("$[0].altitudeMeters").value(120.0))
                .andExpect(jsonPath("$[0].createdByUserId").value(ownerId.value().toString()))
                .andExpect(jsonPath("$[0].groupId").value(groupId.value().toString()))
                .andExpect(jsonPath("$[0].verification").value("UNVERIFIED"))
                .andExpect(jsonPath("$[0].verifiedByUserId").doesNotExist())
                .andExpect(jsonPath("$[0].verifiedAt").doesNotExist())
                .andExpect(jsonPath("$[1].affiliation").value("FRIENDLY"));
    }

    @Test
    void listThreadsTheCallersViewerIntoTheService() throws Exception {
        when(marks.list(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/map/marks")).andExpect(status().isOk());

        ArgumentCaptor<Viewer> viewer = ArgumentCaptor.forClass(Viewer.class);
        verify(marks).list(viewer.capture());
        assertEquals(ownerId, viewer.getValue().userId());
        assertTrue(viewer.getValue().groups().contains(groupId));
    }

    @Test
    void aMarkOnAnInvisibleLayerIsSimplyAbsentRatherThanRejected() throws Exception {
        // MarkService#list filters to canView layers; an out-of-scope mark never reaches the wire.
        when(marks.list(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/map/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aConfirmedMarkCarriesItsReviewerAndReviewInstant() throws Exception {
        UserId reviewer = UserId.random();
        Instant reviewedAt = Instant.parse("2026-08-05T11:30:00Z");
        when(marks.list(any())).thenReturn(List.of(
                mark(MarkId.random(), LayerId.random(), MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        new Verification(VerificationState.CONFIRMED, reviewer, reviewedAt))));

        mockMvc.perform(get("/api/map/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verification").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].verifiedByUserId").value(reviewer.value().toString()))
                .andExpect(jsonPath("$[0].verifiedAt").value("2026-08-05T11:30:00Z"));
    }

    // ---- POST /api/map/marks ----

    @Test
    void createReturns201AndThreadsTheParsedSpecThrough() throws Exception {
        LayerId layerId = LayerId.random();
        MarkId created = MarkId.random();
        when(marks.create(any(), any())).thenReturn(
                mark(created, layerId, MarkKind.EQUIPMENT, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        Verification.unverified()));

        mockMvc.perform(post("/api/map/marks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layerId\":\"" + layerId.value() + "\",\"latitude\":50.45,"
                                + "\"longitude\":30.52,\"altitudeMeters\":120.0,\"kind\":\"equipment\","
                                + "\"affiliation\":\"hostile\",\"label\":\"Radar\",\"note\":\"Two entrances\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.markId").value(created.value().toString()))
                .andExpect(jsonPath("$.kind").value("EQUIPMENT"));

        ArgumentCaptor<MarkSpec> spec = ArgumentCaptor.forClass(MarkSpec.class);
        verify(marks).create(any(), spec.capture());
        assertEquals(layerId, spec.getValue().layerId());
        assertEquals(MarkKind.EQUIPMENT, spec.getValue().kind(), "kind parses case-insensitively");
        assertEquals(Affiliation.HOSTILE, spec.getValue().affiliation());
        assertEquals("Radar", spec.getValue().label());
        assertEquals(new GeoPosition(50.45, 30.52, 120.0), spec.getValue().position());
    }

    @Test
    void createWithNoLayerIdLetsTheServiceResolveTheDefaultLayer() throws Exception {
        when(marks.create(any(), any())).thenReturn(
                mark(MarkId.random(), LayerId.random(), MarkKind.POI, Affiliation.NEUTRAL, MarkStatus.ACTIVE,
                        Verification.unverified()));

        mockMvc.perform(post("/api/map/marks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":50.0,\"longitude\":30.0,\"kind\":\"POI\","
                                + "\"affiliation\":\"NEUTRAL\",\"label\":\"Rally point\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<MarkSpec> spec = ArgumentCaptor.forClass(MarkSpec.class);
        verify(marks).create(any(), spec.capture());
        org.junit.jupiter.api.Assertions.assertNull(spec.getValue().layerId(),
                "an absent layerId means 'resolve my default layer', not a 400");
    }

    @Test
    void createReturns400ForAnUnrecognizedAffiliation() throws Exception {
        mockMvc.perform(post("/api/map/marks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":50.0,\"longitude\":30.0,\"kind\":\"TARGET\","
                                + "\"affiliation\":\"ENEMY\",\"label\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void createReturns400ForTheDeletedFriendlyKind() throws Exception {
        // MarkKind.FRIENDLY is gone -- "whose it is" is now Affiliation. An old client's body 400s
        // loudly rather than being silently reinterpreted.
        mockMvc.perform(post("/api/map/marks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":50.0,\"longitude\":30.0,\"kind\":\"FRIENDLY\","
                                + "\"affiliation\":\"FRIENDLY\",\"label\":\"Nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns403WhenTheCallerMayNotContributeToTheLayer() throws Exception {
        when(marks.create(any(), any())).thenThrow(new AccessDeniedException("not permitted to contribute"));

        mockMvc.perform(post("/api/map/marks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layerId\":\"" + LayerId.random().value() + "\",\"latitude\":50.0,"
                                + "\"longitude\":30.0,\"kind\":\"TARGET\",\"affiliation\":\"HOSTILE\","
                                + "\"label\":\"Nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void createReturns404WhenTheNamedLayerDoesNotExist() throws Exception {
        when(marks.create(any(), any())).thenThrow(new NoSuchElementException("Unknown map layer"));

        mockMvc.perform(post("/api/map/marks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layerId\":\"" + LayerId.random().value() + "\",\"latitude\":50.0,"
                                + "\"longitude\":30.0,\"kind\":\"TARGET\",\"affiliation\":\"HOSTILE\","
                                + "\"label\":\"Nope\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- POST /api/map/marks/geolocate ----

    @Test
    void geolocateReturns201AndDefaultsKindAffiliationLabelButNotDepression() throws Exception {
        AssetId assetId = AssetId.random();
        when(marks.geolocate(any(), any())).thenReturn(new GeolocationResult(
                mark(MarkId.random(), LayerId.random(), MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        Verification.unverified()),
                true));

        mockMvc.perform(post("/api/map/marks/geolocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + assetId.value() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measured").value(true));

        ArgumentCaptor<GeolocateSpec> spec = ArgumentCaptor.forClass(GeolocateSpec.class);
        verify(marks).geolocate(any(), spec.capture());
        assertEquals(assetId, spec.getValue().assetId());
        assertEquals(MarkKind.TARGET, spec.getValue().kind());
        assertEquals(Affiliation.HOSTILE, spec.getValue().affiliation(),
                "a geolocated contact defaults HOSTILE -- what the old MarkKind.TARGET implied");
        assertEquals("Contact", spec.getValue().label());
        org.junit.jupiter.api.Assertions.assertNull(spec.getValue().depressionDegrees(),
                "an absent depressionDegrees must stay null, not silently become the 45deg default -- "
                        + "otherwise every real gimbal measurement would be discarded as a false override "
                        + "(docs/plans/active/GEO-POSE-PLAN.md V3)");
        org.junit.jupiter.api.Assertions.assertNull(spec.getValue().layerId());
    }

    @Test
    void geolocateHonoursAnExplicitLayerAffiliationLabelAndDepressionOverride() throws Exception {
        AssetId assetId = AssetId.random();
        LayerId layerId = LayerId.random();
        when(marks.geolocate(any(), any())).thenReturn(new GeolocationResult(
                mark(MarkId.random(), layerId, MarkKind.HAZARD, Affiliation.UNKNOWN, MarkStatus.ACTIVE,
                        Verification.unverified()),
                false));

        mockMvc.perform(post("/api/map/marks/geolocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + assetId.value() + "\",\"layerId\":\"" + layerId.value()
                                + "\",\"kind\":\"HAZARD\",\"affiliation\":\"UNKNOWN\",\"label\":\"Wire\","
                                + "\"depressionDegrees\":30.0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measured").value(false));

        ArgumentCaptor<GeolocateSpec> spec = ArgumentCaptor.forClass(GeolocateSpec.class);
        verify(marks).geolocate(any(), spec.capture());
        assertEquals(layerId, spec.getValue().layerId());
        assertEquals(MarkKind.HAZARD, spec.getValue().kind());
        assertEquals(Affiliation.UNKNOWN, spec.getValue().affiliation());
        assertEquals("Wire", spec.getValue().label());
        assertEquals(30.0, spec.getValue().depressionDegrees());
    }

    @Test
    void geolocateOmitsMeasuredFromTheWireWhenTheServiceDoesNotReportIt() throws Exception {
        // MarkResponse.from(Mark) (no measured argument) is what every other endpoint uses; asserting
        // the field is entirely absent here -- not merely null -- pins @JsonInclude(NON_NULL) so a
        // MANUAL mark's response never grows a stray "measured": null the way a careless refactor could
        // introduce.
        when(marks.list(any())).thenReturn(List.of(
                mark(MarkId.random(), LayerId.random(), MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        Verification.unverified())));

        mockMvc.perform(get("/api/map/marks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].measured").doesNotExist());
    }

    @Test
    void geolocateReturns400ForABlankAssetId() throws Exception {
        mockMvc.perform(post("/api/map/marks/geolocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void geolocateReturns400WhenTelemetryIsIncomplete() throws Exception {
        when(marks.geolocate(any(), any()))
                .thenThrow(new IllegalArgumentException("cannot geolocate: telemetry incomplete"));

        mockMvc.perform(post("/api/map/marks/geolocate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + AssetId.random().value() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("cannot geolocate: telemetry incomplete"));
    }

    // ---- PATCH /api/map/marks/{id} ----

    @Test
    void patchThreadsOnlyThePresentFieldsThrough() throws Exception {
        MarkId id = MarkId.random();
        when(marks.patch(any(), eq(id), any())).thenReturn(
                mark(id, LayerId.random(), MarkKind.HAZARD, Affiliation.UNKNOWN, MarkStatus.CLEARED,
                        Verification.unverified()));

        mockMvc.perform(patch("/api/map/marks/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"HAZARD\",\"affiliation\":\"UNKNOWN\",\"status\":\"CLEARED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEARED"));

        ArgumentCaptor<MarkPatch> patch = ArgumentCaptor.forClass(MarkPatch.class);
        verify(marks).patch(any(), eq(id), patch.capture());
        assertEquals(Optional.of(MarkKind.HAZARD), patch.getValue().kind());
        assertEquals(Optional.of(Affiliation.UNKNOWN), patch.getValue().affiliation());
        assertEquals(Optional.of(MarkStatus.CLEARED), patch.getValue().status());
        assertEquals(Optional.empty(), patch.getValue().label(), "absent means unchanged");
        assertEquals(Optional.empty(), patch.getValue().position());
    }

    @Test
    void patchMovesTheMarkWhenBothCoordinatesArePresent() throws Exception {
        MarkId id = MarkId.random();
        when(marks.patch(any(), eq(id), any())).thenReturn(
                mark(id, LayerId.random(), MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        Verification.unverified()));

        mockMvc.perform(patch("/api/map/marks/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":51.0,\"longitude\":31.0,\"altitudeMeters\":80.0}"))
                .andExpect(status().isOk());

        ArgumentCaptor<MarkPatch> patch = ArgumentCaptor.forClass(MarkPatch.class);
        verify(marks).patch(any(), eq(id), patch.capture());
        assertEquals(Optional.of(new GeoPosition(51.0, 31.0, 80.0)), patch.getValue().position());
    }

    @Test
    void patchReturns400WhenOnlyOneHalfOfTheCoordinatePairIsSupplied() throws Exception {
        mockMvc.perform(patch("/api/map/marks/" + MarkId.random().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":51.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchReturns404ForAnUnknownMark() throws Exception {
        MarkId id = MarkId.random();
        when(marks.patch(any(), eq(id), any())).thenThrow(new NoSuchElementException("Unknown mark"));

        mockMvc.perform(patch("/api/map/marks/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nope\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchReturns403WhenTheCallerIsNeitherCreatorNorManager() throws Exception {
        MarkId id = MarkId.random();
        when(marks.patch(any(), eq(id), any())).thenThrow(new AccessDeniedException("not permitted"));

        mockMvc.perform(patch("/api/map/marks/" + id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- POST /api/map/marks/{id}/verify ----

    @Test
    void verifyReturns200WithTheReviewedMark() throws Exception {
        MarkId id = MarkId.random();
        UserId reviewer = UserId.random();
        when(marks.verify(any(), eq(id), eq(VerificationState.CONFIRMED))).thenReturn(
                mark(id, LayerId.random(), MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        new Verification(VerificationState.CONFIRMED, reviewer, Instant.parse("2026-08-05T12:00:00Z"))));

        mockMvc.perform(post("/api/map/marks/" + id.value() + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification").value("CONFIRMED"))
                .andExpect(jsonPath("$.verifiedByUserId").value(reviewer.value().toString()));
    }

    @Test
    void verifyReturns403ForANonManager() throws Exception {
        MarkId id = MarkId.random();
        when(marks.verify(any(), eq(id), any()))
                .thenThrow(new AccessDeniedException("not permitted to manage layer"));

        mockMvc.perform(post("/api/map/marks/" + id.value() + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void verifyReturns400ForAnUnrecognizedDecision() throws Exception {
        mockMvc.perform(post("/api/map/marks/" + MarkId.random().value() + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyReturns404ForAnUnknownMark() throws Exception {
        MarkId id = MarkId.random();
        when(marks.verify(any(), eq(id), any())).thenThrow(new NoSuchElementException("Unknown mark"));

        mockMvc.perform(post("/api/map/marks/" + id.value() + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- POST /api/map/marks/{id}/promote ----

    @Test
    void promoteWithNoBodyTargetsTheCopLayer() throws Exception {
        MarkId id = MarkId.random();
        when(marks.promote(any(), eq(id), isNull())).thenReturn(
                mark(id, LayerId.random(), MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        new Verification(VerificationState.CONFIRMED, ownerId, Instant.now())));

        mockMvc.perform(post("/api/map/marks/" + id.value() + "/promote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification").value("CONFIRMED"));

        verify(marks).promote(any(), eq(id), isNull());
    }

    @Test
    void promoteHonoursAnExplicitTargetLayer() throws Exception {
        MarkId id = MarkId.random();
        LayerId target = LayerId.random();
        when(marks.promote(any(), eq(id), eq(target))).thenReturn(
                mark(id, target, MarkKind.TARGET, Affiliation.HOSTILE, MarkStatus.ACTIVE,
                        new Verification(VerificationState.CONFIRMED, ownerId, Instant.now())));

        mockMvc.perform(post("/api/map/marks/" + id.value() + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetLayerId\":\"" + target.value() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layerId").value(target.value().toString()));
    }

    @Test
    void promoteReturns403WhenTheCallerDoesNotManageTheSourceLayer() throws Exception {
        MarkId id = MarkId.random();
        when(marks.promote(any(), eq(id), any()))
                .thenThrow(new AccessDeniedException("not permitted to manage layer"));

        mockMvc.perform(post("/api/map/marks/" + id.value() + "/promote"))
                .andExpect(status().isForbidden());
    }

    @Test
    void promoteReturns400ForAMalformedTargetLayerId() throws Exception {
        mockMvc.perform(post("/api/map/marks/" + MarkId.random().value() + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetLayerId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- DELETE /api/map/marks/{id} ----

    @Test
    void deleteReturns204() throws Exception {
        MarkId id = MarkId.random();

        mockMvc.perform(delete("/api/map/marks/" + id.value()))
                .andExpect(status().isNoContent());

        verify(marks).delete(any(), eq(id));
    }

    @Test
    void deleteReturns403WhenTheCallerIsNeitherCreatorNorManager() throws Exception {
        MarkId id = MarkId.random();
        doThrow(new AccessDeniedException("not permitted")).when(marks).delete(any(), eq(id));

        mockMvc.perform(delete("/api/map/marks/" + id.value()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteReturns400ForAMalformedMarkId() throws Exception {
        mockMvc.perform(delete("/api/map/marks/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
