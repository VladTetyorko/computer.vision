package com.drones.vision.api.demo;

import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.identity.AssignmentService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoScenarioTest {

    private DemoPeople people;
    private DemoFleet fleet;
    private DemoOperations operations;
    private AssignmentService assignments;
    private DemoScenario scenario;

    private final Ownership ownership = new Ownership(UserId.random(), GroupId.random());

    @BeforeEach
    void setUp() {
        people = mock(DemoPeople.class);
        fleet = mock(DemoFleet.class);
        operations = mock(DemoOperations.class);
        assignments = mock(AssignmentService.class);
        scenario = new DemoScenario(people, fleet, operations, assignments, new CurrentUser(ownership));
    }

    @Test
    void seedsPeopleFleetAssignmentsZonesMarksAndStreams() {
        List<User> roster = users(3);
        List<DemoAsset> created = assets(4);
        when(people.seed(eq(3), any(), any())).thenReturn(roster);
        when(fleet.seed(eq(4), eq(ownership), eq(ownership.ownerId()), any())).thenReturn(created);
        when(fleet.videosUsed(created)).thenReturn(List.of("drone.mp4"));
        when(operations.seedZones(any())).thenReturn(2);
        when(operations.seedMarks(eq(ownership), eq(ownership.ownerId()), any())).thenReturn(5);
        when(fleet.startStreams(eq(created), eq(2), any())).thenReturn(2);

        DemoSeedReport report = scenario.seed(new DemoPlan(4, 3, 2));

        assertEquals(4, report.assetNames().size());
        assertEquals(3, report.usernames().size());
        assertEquals(2, report.zones());
        assertEquals(5, report.marks());
        assertEquals(2, report.streamsStarted());
        assertEquals(List.of("drone.mp4"), report.videosUsed());
        assertTrue(report.problems().isEmpty());
    }

    @Test
    void assignsEveryAssetToAPilotRoundRobinPlusASecondPilotOnEveryThird() {
        List<User> roster = users(2);
        List<DemoAsset> created = assets(3);
        when(people.seed(anyInt(), any(), any())).thenReturn(roster);
        when(fleet.seed(anyInt(), any(), any(), any())).thenReturn(created);
        when(fleet.videosUsed(any())).thenReturn(List.of());

        DemoSeedReport report = scenario.seed(new DemoPlan(3, 2, 0));

        // 3 assets, one pilot each, plus a second pilot on asset index 0 (every third).
        assertEquals(4, report.assignments());
        ArgumentCaptor<AssetId> assets = ArgumentCaptor.forClass(AssetId.class);
        verify(assignments, times(4)).assign(any(), assets.capture(), eq(VisibilityScope.unbounded()));
        assertEquals(created.stream().map(DemoAsset::id).distinct().count(),
                assets.getAllValues().stream().distinct().count(),
                "every created asset was assigned at least once");
    }

    @Test
    void aFailedAssignmentIsReportedRatherThanThrown() {
        when(people.seed(anyInt(), any(), any())).thenReturn(users(1));
        when(fleet.seed(anyInt(), any(), any(), any())).thenReturn(assets(1));
        when(fleet.videosUsed(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            throw new IllegalStateException("asset out of scope");
        }).when(assignments).assign(any(), any(), any());

        DemoSeedReport report = scenario.seed(new DemoPlan(1, 1, 0));

        assertEquals(0, report.assignments());
        assertEquals(1, report.problems().size());
        assertTrue(report.problems().getFirst().contains("asset out of scope"), report.problems().toString());
    }

    @Test
    void problemsReportedByEachSeederAreCollectedIntoTheReport() {
        when(people.seed(anyInt(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Consumer.class).accept("user demo.falcon: taken");
            return List.<User>of();
        });
        when(fleet.seed(anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Consumer.class).accept("asset FPV Pis-UN: no such category");
            return List.<DemoAsset>of();
        });
        when(fleet.videosUsed(any())).thenReturn(List.of());

        DemoSeedReport report = scenario.seed(DemoPlan.DEFAULT);

        assertEquals(List.of("user demo.falcon: taken", "asset FPV Pis-UN: no such category"), report.problems());
    }

    private static List<User> users(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new User(UserId.random(), "demo.pilot" + index, "Demo " + index,
                        "demo" + index + "@demo.local", "hash", true))
                .toList();
    }

    private static List<DemoAsset> assets(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new DemoAsset(AssetId.random(), "Airframe " + (index + 1), "drone.mp4"))
                .toList();
    }
}
