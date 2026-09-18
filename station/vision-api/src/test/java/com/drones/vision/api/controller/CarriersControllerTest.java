package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.flight.domain.model.CarrierKind;
import com.drones.vision.flight.domain.model.CarrierView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.flight.domain.model.SerialRole;
import com.drones.vision.flight.domain.port.CarrierDirectoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LINK-PAIRING-PLAN.md §3.4/§7 ruling 5 — {@code GET /api/carriers} is station-wide reference data,
 * not per-asset (see {@link CarriersController}'s own javadoc), so unlike every {@code
 * AssetLinksControllerTest} case there is no scope to guard: {@link CarriersController} is
 * {@code @OpenByDesign} and takes no {@code CurrentUser} at all.
 */
class CarriersControllerTest {

    private CarrierDirectoryPort carrierDirectoryPort;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        carrierDirectoryPort = mock(CarrierDirectoryPort.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CarriersController(carrierDirectoryPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listReturns200WithEveryRegisteredCarrierMapped() throws Exception {
        LinkId id = new LinkId("udp:127.0.0.1:14550");
        when(carrierDirectoryPort.carriers())
                .thenReturn(List.of(new CarrierView(id, CarrierKind.UDP, SerialRole.NONE, "Primary UDP", 10)));

        mockMvc.perform(get("/api/carriers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.value()))
                .andExpect(jsonPath("$[0].carrier").value("UDP"))
                .andExpect(jsonPath("$[0].serialRole").value("NONE"))
                .andExpect(jsonPath("$[0].label").value("Primary UDP"))
                .andExpect(jsonPath("$[0].priority").value(10));
    }

    @Test
    void listReturns200WithAnEmptyListWhenNoCarrierHasEverRegistered() throws Exception {
        when(carrierDirectoryPort.carriers()).thenReturn(List.of());

        mockMvc.perform(get("/api/carriers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
