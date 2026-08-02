package com.drones.vision.api.controller;

import com.drones.vision.api.dto.NetworkAddressResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import com.drones.vision.api.support.LocalNetworkAddresses;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link SystemNetworkController} (docs/DRONE-INFRA-PLAN.md I-g's frozen wire
 * contract), same {@code standaloneSetup} style as {@link CategoryControllerTest}. {@link
 * LocalNetworkAddresses} — this controller's one collaborator — is a mocked test double here
 * (its own filtering/sorting behavior is {@link LocalNetworkAddressesTest}'s job); these tests
 * exist to prove the JSON shape, the empty-addresses case, and that {@code mavlinkPort} is
 * whatever value the controller was constructed with, not a hardcoded literal.
 */
class SystemNetworkControllerTest {

    private static final int MAVLINK_PORT = 14_550;

    private LocalNetworkAddresses localNetworkAddresses;
    private MockMvc mockMvc;

    private void setUp(int mavlinkPort) {
        localNetworkAddresses = mock(LocalNetworkAddresses.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemNetworkController(mavlinkPort, localNetworkAddresses))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void networkReturns200WithAddressesAndTheConfiguredMavlinkPort() throws Exception {
        setUp(MAVLINK_PORT);
        when(localNetworkAddresses.list()).thenReturn(
                List.of(new NetworkAddressResponse("192.168.0.104", "wlp2s0")));

        mockMvc.perform(get("/api/system/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses", hasSize(1)))
                .andExpect(jsonPath("$.addresses[0].address").value("192.168.0.104"))
                .andExpect(jsonPath("$.addresses[0].interfaceName").value("wlp2s0"))
                .andExpect(jsonPath("$.mavlinkPort").value(MAVLINK_PORT));
    }

    @Test
    void networkReturns200WithAnEmptyAddressListRatherThanAnError() throws Exception {
        setUp(MAVLINK_PORT);
        when(localNetworkAddresses.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/system/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses", hasSize(0)))
                .andExpect(jsonPath("$.mavlinkPort").value(MAVLINK_PORT));
    }

    @Test
    void networkReportsWhicheverMavlinkPortTheControllerWasConfiguredWith() throws Exception {
        int nonDefaultPort = 25_000;
        setUp(nonDefaultPort);
        when(localNetworkAddresses.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/system/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mavlinkPort").value(nonDefaultPort));
    }
}
