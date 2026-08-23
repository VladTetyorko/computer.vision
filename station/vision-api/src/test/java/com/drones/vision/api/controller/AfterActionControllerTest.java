package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.afteraction.AfterActionAssembler;
import com.drones.vision.api.support.afteraction.AfterActionPackage;
import com.drones.vision.api.support.afteraction.AfterActionPart;
import com.drones.vision.api.support.afteraction.AfterActionPartKind;
import com.drones.vision.api.support.afteraction.AfterActionPartState;
import com.drones.vision.events.application.UsageRecording;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage of {@link AfterActionController} (docs/plans/done/AFTER-ACTION-PLAN.md, the
 * frozen wire contract &sect;3) — the two endpoints' happy paths and every &sect;3.3 error
 * mapping. Mocks {@link AfterActionAssembler} directly (a concrete final class; this module's
 * Mockito already mocks final application classes the same way, e.g. {@code
 * OnboardingControllerTest} mocking {@code RemediationOrchestrator}), so this class proves only
 * the HTTP/DTO/ZIP wiring — {@link AfterActionAssembler}'s own logic is covered without Spring in
 * {@code AfterActionAssemblerTest}.
 */
class AfterActionControllerTest {

    private final AssetId assetId = AssetId.random();
    private final UsageId usageId = UsageId.random();
    private final Instant startedAt = Instant.parse("2026-08-18T09:00:00Z");
    private final Instant endedAt = Instant.parse("2026-08-18T09:30:00Z");

    private AfterActionAssembler assembler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assembler = mock(AfterActionAssembler.class);
        CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders.standaloneSetup(new AfterActionController(assembler, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ---- manifest ----

    @Test
    void manifestReturns200WithAllSixPartsAndHonestNullFields() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any())).thenReturn(fullyPresentPackage());

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action", assetId.value(), usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.usageId").value(usageId.value().toString()))
                .andExpect(jsonPath("$.assetName").value("Drone One"))
                .andExpect(jsonPath("$.startedAt").value("2026-08-18T09:00:00Z"))
                .andExpect(jsonPath("$.endedAt").value("2026-08-18T09:30:00Z"))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.scopedTo").value("referee"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.parts", org.hamcrest.Matchers.hasSize(6)))
                .andExpect(jsonPath("$.parts[0].part").value("telemetry"))
                .andExpect(jsonPath("$.parts[0].state").value("PRESENT"))
                // no @JsonInclude(NON_NULL) on this DTO -- note: null must appear on the wire, not
                // be omitted (AfterActionPartResponse's own javadoc).
                .andExpect(jsonPath("$.parts[0].note").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.parts[5].part").value("audit"))
                // marks (index 2) is PRESENT but still carries its standing D5 note -- the corrected
                // caveats rule this wave applied: a PRESENT part's note still counts as a caveat.
                .andExpect(jsonPath("$.parts[2].note")
                        .value("marks created inside the flight window and visible to you; a mark is not bound to a flight"))
                .andExpect(jsonPath("$.caveats", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.caveats[0]").value(
                        "marks created inside the flight window and visible to you; a mark is not bound to a flight"));
    }

    @Test
    void manifestReturns200WithNullEndedAtForAStillOpenUsage() throws Exception {
        AfterActionPackage open = new AfterActionPackage(assetId, "Drone One", usageId, startedAt, null, true,
                Instant.now(), "referee", allAbsentParts(), List.of(), List.of(), List.of(),
                new FlightPassport(usageId, assetId, null, null), Optional.empty(), List.of());
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any())).thenReturn(open);

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action", assetId.value(), usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.open").value(true));
    }

    @Test
    void manifestReturns404ForAnUnknownOrOutOfScopeAsset() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any()))
                .thenThrow(new NoSuchElementException("No asset with id " + assetId.value()));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action", assetId.value(), usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void manifestReturns404WhenTheUsageDoesNotBelongToTheAsset() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any()))
                .thenThrow(new NoSuchElementException("No usage " + usageId.value()));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action", assetId.value(), usageId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void manifestReturns403WhenTheViewerMaySeeButNotExportTheAsset() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any()))
                .thenThrow(new AccessDeniedException("Not permitted to export the after-action package"));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action", assetId.value(), usageId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void manifestReturns400ForAMalformedAssetId() throws Exception {
        mockMvc.perform(
                        get("/api/assets/{assetId}/usages/{usageId}/after-action", "not-a-uuid", usageId.value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void manifestReturns400ForAMalformedUsageId() throws Exception {
        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action", assetId.value(), "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- archive ----

    @Test
    void archiveStreamsAZipWithAllEightEntriesInFixedOrder() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any())).thenReturn(fullyPresentPackage());

        MvcResult started = mockMvc.perform(
                        get("/api/assets/{assetId}/usages/{usageId}/after-action/archive", assetId.value(),
                                usageId.value()))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"after-action-" + usageId.value() + ".zip\""))
                .andReturn();

        List<String> entryNames = entryNamesOf(result.getResponse().getContentAsByteArray());
        assertEquals(List.of("manifest.json", "README.txt", "telemetry.csv", "detections.csv", "marks.geojson",
                "passport.json", "audit.csv", "recording.txt"), entryNames);
    }

    @Test
    void archiveReturns404BeforeAnyAsyncDispatchForAnUnknownAsset() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any()))
                .thenThrow(new NoSuchElementException("No asset with id " + assetId.value()));

        // Resolution happens synchronously before the StreamingResponseBody is ever returned, so a
        // 404 never starts an async dispatch at all.
        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action/archive", assetId.value(),
                        usageId.value()))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void archiveReturns403BeforeAnyAsyncDispatchWhenTheViewerMayNotExport() throws Exception {
        when(assembler.assemble(eq(assetId), eq(usageId), any(), any(), any()))
                .thenThrow(new AccessDeniedException("Not permitted to export the after-action package"));

        mockMvc.perform(get("/api/assets/{assetId}/usages/{usageId}/after-action/archive", assetId.value(),
                        usageId.value()))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // ---- fixtures ----

    private AfterActionPackage fullyPresentPackage() {
        List<AfterActionPart> parts = List.of(
                new AfterActionPart(AfterActionPartKind.TELEMETRY, AfterActionPartState.PRESENT, 10, null),
                new AfterActionPart(AfterActionPartKind.DETECTIONS, AfterActionPartState.PRESENT, 3, null),
                new AfterActionPart(AfterActionPartKind.MARKS, AfterActionPartState.PRESENT, 1,
                        "marks created inside the flight window and visible to you; a mark is not bound to a flight"),
                new AfterActionPart(AfterActionPartKind.RECORDING, AfterActionPartState.PRESENT, 1, null),
                new AfterActionPart(AfterActionPartKind.PASSPORT, AfterActionPartState.PRESENT, 2, null),
                new AfterActionPart(AfterActionPartKind.AUDIT, AfterActionPartState.PRESENT, 1, null));
        return new AfterActionPackage(assetId, "Drone One", usageId, startedAt, endedAt, false, Instant.now(),
                "referee", parts, List.of(), List.of(), List.of(),
                new FlightPassport(usageId, assetId, null, null),
                Optional.of(new UsageRecording(java.net.URI.create("rtsp://mediamtx/clip"), startedAt, 1800)),
                List.of());
    }

    private List<AfterActionPart> allAbsentParts() {
        return List.of(
                new AfterActionPart(AfterActionPartKind.TELEMETRY, AfterActionPartState.ABSENT, 0, "no telemetry"),
                new AfterActionPart(AfterActionPartKind.DETECTIONS, AfterActionPartState.ABSENT, 0, "no detections"),
                new AfterActionPart(AfterActionPartKind.MARKS, AfterActionPartState.ABSENT, 0, "no marks"),
                new AfterActionPart(AfterActionPartKind.RECORDING, AfterActionPartState.ABSENT, 0, "no recording"),
                new AfterActionPart(AfterActionPartKind.PASSPORT, AfterActionPartState.ABSENT, 0, "no passport"),
                new AfterActionPart(AfterActionPartKind.AUDIT, AfterActionPartState.ABSENT, 0, "no audit"));
    }

    private static List<String> entryNamesOf(byte[] zipBytes) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
