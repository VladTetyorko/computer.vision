package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.RegisterFromCandidateCommand;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import com.drones.vision.warehouse.domain.model.Identity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link DiscoveryInboxController} (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c) — mirrors {@link AuditControllerTest}'s own
 * pattern for a controller with no application-service layer of its own to put an authorization
 * check in, plus the pass-through/delegated authorization {@link #register} exercises.
 */
class DiscoveryInboxControllerTest {

    private final DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);

    private static CurrentUser currentUserWithScope(VisibilityScope scope) {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownership.ownerId();
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                return new MapAccessPolicy.Viewer(ownership.ownerId(), Set.of(ownership.groupId()), Role.PILOT);
            }
        });
    }

    private static MockMvc mockMvcFor(CurrentUser currentUser, DiscoveryInboxService discoveryInboxService) {
        return MockMvcBuilders.standaloneSetup(new DiscoveryInboxController(discoveryInboxService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DiscoveredDevice discoveredDevice() {
        return new DiscoveredDevice("mavlink", "New quad", URI.create("udp://10.0.0.5:14550"),
                new CategoryId("quadcopter"),
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.5:14550"), Map.of("sysid", "7")),
                Map.of("sysid", "7"));
    }

    private static DiscoveryCandidate candidate() {
        return DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discoveredDevice(), Instant.now());
    }

    // --- list ---

    @Test
    void listSucceedsForAnAdminUnboundedScopeAndCarriesTheFullSuggestedStream() throws Exception {
        when(discoveryInboxService.candidates()).thenReturn(List.of(candidate()));
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), discoveryInboxService);

        mockMvc.perform(get("/api/discovery/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].method").value("mavlink"))
                .andExpect(jsonPath("$[0].suggestedStreamProtocol").value("mavlink"))
                .andExpect(jsonPath("$[0].suggestedStreamOptions.sysid").value("7"))
                .andExpect(jsonPath("$[0].status").value("NEW"));
    }

    @Test
    void listReturns403ForAPilotAssignedAssetsScope() throws Exception {
        MockMvc mockMvc = mockMvcFor(
                currentUserWithScope(VisibilityScope.assignedAssets(Set.of(AssetId.random()))), discoveryInboxService);

        mockMvc.perform(get("/api/discovery/inbox"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(discoveryInboxService);
    }

    // --- dismiss ---

    @Test
    void dismissSucceedsForAManagerGroupsScope() throws Exception {
        DiscoveryCandidate dismissed = candidate().dismiss();
        when(discoveryInboxService.dismiss(any(), any())).thenReturn(dismissed);
        MockMvc mockMvc = mockMvcFor(
                currentUserWithScope(VisibilityScope.groups(Set.of(GroupId.random()))), discoveryInboxService);

        mockMvc.perform(post("/api/discovery/inbox/" + dismissed.id().value() + "/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void dismissReturns403ForAnUnaffiliatedEmptyScope() throws Exception {
        MockMvc mockMvc = mockMvcFor(
                currentUserWithScope(VisibilityScope.assignedAssets(Set.of())), discoveryInboxService);

        mockMvc.perform(post("/api/discovery/inbox/" + DiscoveryCandidateId.random().value() + "/dismiss"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(discoveryInboxService);
    }

    @Test
    void dismissReturns404WhenTheServiceReportsAnUnknownCandidate() throws Exception {
        when(discoveryInboxService.dismiss(any(), any()))
                .thenThrow(new NoSuchElementException("Unknown discovery candidate"));
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), discoveryInboxService);

        mockMvc.perform(post("/api/discovery/inbox/" + DiscoveryCandidateId.random().value() + "/dismiss"))
                .andExpect(status().isNotFound());
    }

    // --- register ---

    @Test
    void registerDelegatesToTheServiceAndMapsTheCreatedAssetOnSuccess() throws Exception {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        Asset created = Asset.register(AssetId.random(), "new quad", new CategoryId("quadcopter"), ownership,
                Set.of(), Map.of(), Identity.NONE, Custody.NONE);
        when(discoveryInboxService.register(any(), any(), any(), any())).thenReturn(created);
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), discoveryInboxService);

        String body = "{\"displayName\":\"new quad\",\"category\":\"quadcopter\"}";
        mockMvc.perform(post("/api/discovery/inbox/" + DiscoveryCandidateId.random().value() + "/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(created.id().value().toString()))
                .andExpect(jsonPath("$.displayName").value("new quad"))
                .andExpect(jsonPath("$.category").value("quadcopter"));
    }

    @Test
    void registerPropagatesAccessDeniedFromTheServiceAs403() throws Exception {
        when(discoveryInboxService.register(any(), any(), any(), any()))
                .thenThrow(new AccessDeniedException("not permitted"));
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), discoveryInboxService);

        String body = "{\"displayName\":\"new quad\",\"category\":\"quadcopter\"}";
        mockMvc.perform(post("/api/discovery/inbox/" + DiscoveryCandidateId.random().value() + "/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerPropagatesNoSuchElementFromTheServiceAs404() throws Exception {
        when(discoveryInboxService.register(any(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("Unknown discovery candidate"));
        MockMvc mockMvc = mockMvcFor(currentUserWithScope(VisibilityScope.unbounded()), discoveryInboxService);

        String body = "{\"displayName\":\"new quad\",\"category\":\"quadcopter\"}";
        mockMvc.perform(post("/api/discovery/inbox/" + DiscoveryCandidateId.random().value() + "/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerPassesTheAskingUsersOwnershipNotTheRequestBody() throws Exception {
        CurrentUser currentUser = currentUserWithScope(VisibilityScope.unbounded());
        Asset created = Asset.register(AssetId.random(), "new quad", new CategoryId("quadcopter"),
                currentUser.ownership(), Set.of(), Map.of(), Identity.NONE, Custody.NONE);
        ArgumentCaptor<RegisterFromCandidateCommand> captor = ArgumentCaptor.forClass(RegisterFromCandidateCommand.class);
        when(discoveryInboxService.register(any(), captor.capture(), any(), any())).thenReturn(created);
        MockMvc mockMvc = mockMvcFor(currentUser, discoveryInboxService);

        String body = "{\"displayName\":\"new quad\",\"category\":\"quadcopter\"}";
        mockMvc.perform(post("/api/discovery/inbox/" + DiscoveryCandidateId.random().value() + "/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(currentUser.ownership(), captor.getValue().ownership());
    }
}
