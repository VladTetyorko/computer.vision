package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SeatHolderResponse;
import com.drones.vision.api.dto.SeatsResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.SeatAccess;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Driving-adapter test for {@link SeatController} (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.6,
 * wave W2) — thin HTTP-shape translation, so {@link SeatAccess} is mocked directly (the same idiom
 * {@code LiveControllerTest} establishes for a collaborator whose own policy is separately unit
 * tested — see {@code SeatAccessTest}). Exercises parsing (bad UUID/kind &rarr; 400), the visibility
 * gate ordering, delegation, and the exception-handler-mapped status codes.
 */
class SeatControllerTest {

    private SeatAccess seatAccess;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        seatAccess = mock(SeatAccess.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SeatController(seatAccess))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static SeatsResponse freeSeats(AssetId assetId) {
        return new SeatsResponse(assetId.value().toString(), 15_000L, SeatHolderResponse.free(),
                SeatHolderResponse.free(), true, true, true);
    }

    // ---- GET /api/assets/{id}/seats ----

    @Test
    void seatsReturnsTheFrozenShape() throws Exception {
        AssetId assetId = AssetId.random();
        when(seatAccess.seats(assetId)).thenReturn(freeSeats(assetId));

        mockMvc.perform(get("/api/assets/{id}/seats", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.ttlMs").value(15_000))
                .andExpect(jsonPath("$.flight.holderUserId").doesNotExist())
                .andExpect(jsonPath("$.mayTakeFlight").value(true));

        verify(seatAccess).requireVisibleAsset(assetId);
    }

    @Test
    void seatsReturns400ForAMalformedAssetId() throws Exception {
        mockMvc.perform(get("/api/assets/{id}/seats", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void seatsReturns404WhenTheVisibilityGateRefuses() throws Exception {
        AssetId assetId = AssetId.random();
        doThrow(new NoSuchElementException("Unknown asset: " + assetId.value()))
                .when(seatAccess).requireVisibleAsset(assetId);

        mockMvc.perform(get("/api/assets/{id}/seats", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- POST /api/assets/{id}/seats/{kind} ----

    @Test
    void takeReturns200AndTheResultingSeats() throws Exception {
        AssetId assetId = AssetId.random();
        when(seatAccess.takeSeat(eq(assetId), eq(SeatKind.FLIGHT), eq(false))).thenReturn(freeSeats(assetId));

        mockMvc.perform(post("/api/assets/{id}/seats/{kind}", assetId.value(), "flight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()));

        verify(seatAccess).requireVisibleAsset(assetId);
        verify(seatAccess).takeSeat(assetId, SeatKind.FLIGHT, false);
    }

    @Test
    void takeWithForceBodyThreadsTheFlagThrough() throws Exception {
        AssetId assetId = AssetId.random();
        when(seatAccess.takeSeat(eq(assetId), eq(SeatKind.CAMERA), eq(true))).thenReturn(freeSeats(assetId));

        mockMvc.perform(post("/api/assets/{id}/seats/{kind}", assetId.value(), "camera")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":true}"))
                .andExpect(status().isOk());

        verify(seatAccess).takeSeat(assetId, SeatKind.CAMERA, true);
    }

    @Test
    void takeReturns400ForAnUnknownKind() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(post("/api/assets/{id}/seats/{kind}", assetId.value(), "wings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void takeReturns403WhenTheCallerHasNoStanding() throws Exception {
        AssetId assetId = AssetId.random();
        when(seatAccess.takeSeat(eq(assetId), eq(SeatKind.FLIGHT), eq(false)))
                .thenThrow(new AccessDeniedException("Asset " + assetId.value() + " flight seat may not be taken by you"));

        mockMvc.perform(post("/api/assets/{id}/seats/{kind}", assetId.value(), "flight"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void takeReturns409WhenTheSeatIsHeldByAnother() throws Exception {
        AssetId assetId = AssetId.random();
        when(seatAccess.takeSeat(eq(assetId), eq(SeatKind.FLIGHT), eq(false)))
                .thenThrow(new IllegalStateException(
                        "Asset " + assetId.value() + " flight seat is held by Anna Kovalenko"));

        mockMvc.perform(post("/api/assets/{id}/seats/{kind}", assetId.value(), "flight"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Asset " + assetId.value() + " flight seat is held by Anna Kovalenko"));
    }

    @Test
    void takeReturns404WhenTheAssetIsUnknownBeforeAnyDelegation() throws Exception {
        AssetId assetId = AssetId.random();
        doThrow(new NoSuchElementException("Unknown asset: " + assetId.value()))
                .when(seatAccess).requireVisibleAsset(assetId);

        mockMvc.perform(post("/api/assets/{id}/seats/{kind}", assetId.value(), "flight"))
                .andExpect(status().isNotFound());

        verify(seatAccess).requireVisibleAsset(assetId);
        verifyNoMoreInteractions(seatAccess);
    }

    // ---- DELETE /api/assets/{id}/seats/{kind} ----

    @Test
    void releaseReturns204() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(delete("/api/assets/{id}/seats/{kind}", assetId.value(), "camera"))
                .andExpect(status().isNoContent());

        verify(seatAccess).requireVisibleAsset(assetId);
        verify(seatAccess).releaseSeat(assetId, SeatKind.CAMERA);
    }

    @Test
    void releaseReturns403WhenHeldByAnotherAndCallerMayNotForce() throws Exception {
        AssetId assetId = AssetId.random();
        doThrow(new AccessDeniedException(
                        "Asset " + assetId.value() + " flight seat is held by Anna Kovalenko"))
                .when(seatAccess).releaseSeat(assetId, SeatKind.FLIGHT);

        mockMvc.perform(delete("/api/assets/{id}/seats/{kind}", assetId.value(), "flight"))
                .andExpect(status().isForbidden());
    }

    @Test
    void releaseReturns400ForAnUnknownKind() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(delete("/api/assets/{id}/seats/{kind}", assetId.value(), "wings"))
                .andExpect(status().isBadRequest());
    }
}
