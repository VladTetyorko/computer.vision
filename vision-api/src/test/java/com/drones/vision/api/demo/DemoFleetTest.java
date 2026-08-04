package com.drones.vision.api.demo;

import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatus;
import com.drones.vision.application.asset.AssetSummary;
import com.drones.vision.application.simulation.SimulatedAsset;
import com.drones.vision.application.simulation.SimulationService;
import com.drones.vision.application.simulation.SimulationSpec;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoFleetTest {

    @TempDir
    Path videoFolder;

    private SimulationService simulations;
    private AssetService assets;

    private final Ownership ownership = new Ownership(UserId.random(), GroupId.random());

    @BeforeEach
    void setUp() {
        simulations = mock(SimulationService.class);
        assets = mock(AssetService.class);
        when(simulations.simulate(any(), any(), any()))
                .thenAnswer(invocation -> new SimulatedAsset(AssetId.random(), null));
        when(assets.assets(anyBoolean())).thenReturn(List.of());
    }

    @Test
    void cyclesThroughTheVideoFolderWhenThereAreMoreAssetsThanFiles() throws IOException {
        Files.createFile(videoFolder.resolve("a.mp4"));
        Files.createFile(videoFolder.resolve("b.mp4"));

        List<DemoAsset> created = fleet().seed(3, ownership, ownership.ownerId(), problem -> { });

        assertEquals(List.of("a.mp4", "b.mp4", "a.mp4"), created.stream().map(DemoAsset::videoName).toList());
        assertEquals(List.of("a.mp4", "b.mp4"), fleet().videosUsed(created));
    }

    @Test
    void fallsBackToFullySyntheticAssetsWhenTheFolderHasNoVideos() {
        List<DemoAsset> created = fleet().seed(2, ownership, ownership.ownerId(), problem -> { });

        assertEquals(2, created.size());
        created.forEach(asset -> assertNull(asset.videoName()));
        ArgumentCaptor<SimulationSpec> spec = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulations, times(2)).simulate(spec.capture(), any(), any());
        spec.getAllValues().forEach(value -> assertNull(value.videoPath()));
    }

    @Test
    void spreadsHomePointsAndNeverAutoStartsDuringCreation() {
        fleet().seed(3, ownership, ownership.ownerId(), problem -> { });

        ArgumentCaptor<SimulationSpec> spec = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulations, times(3)).simulate(spec.capture(), any(), any());
        List<SimulationSpec> specs = spec.getAllValues();
        assertEquals(3, specs.stream().map(value -> List.of(value.latitude(), value.longitude())).distinct().count(),
                "each asset gets its own home point on the ring");
        specs.forEach(value -> {
            assertTrue(!value.autoStart(), "creation never auto-starts — startStreams() is a separate pass");
            assertEquals(4, value.plan().route().size(), "each asset patrols its own four-point route");
        });
    }

    @Test
    void callSignsSkipTheAirframesAlreadyRegistered() {
        // "Shark" is unrelated to the demo roster's own order — the point is that a name in use is
        // stepped over wherever it sits, not that allocation resumes after some highest number.
        when(assets.assets(anyBoolean())).thenReturn(List.of(summary("FPV Pis-UN"), summary("Shark"),
                summary("Falcon-2")));

        List<DemoAsset> created = fleet().seed(3, ownership, ownership.ownerId(), problem -> { });

        assertEquals(List.of("FPV Vyriy", "Skyfall Vampire", "Bayraktar TB2"),
                created.stream().map(DemoAsset::displayName).toList());
    }

    @Test
    void callSignsCycleWithASuffixOnceTheRosterIsExhausted() {
        when(assets.assets(anyBoolean())).thenReturn(List.of());

        // 15 = one full roster (14) plus one, so the sixteenth press has to lap.
        List<DemoAsset> created = fleet().seed(15, ownership, ownership.ownerId(), problem -> { });

        List<String> names = created.stream().map(DemoAsset::displayName).toList();
        assertEquals("FPV Pis-UN", names.get(0));
        assertEquals("FPV Pis-UN 2", names.get(14), "laps the roster rather than repeating a name");
        assertEquals(15, names.stream().distinct().count(), "no duplicates within one press");
    }

    @Test
    void aSecondPressExtendsTheFleetInsteadOfMintingDuplicates() {
        // Everything the first press would have used is already registered, including a soft-deleted
        // one — `assets(true)` is why the archive view cannot end up with two of the same name.
        when(assets.assets(anyBoolean())).thenReturn(List.of(summary("FPV Pis-UN"), summary("FPV Vyriy")));

        List<DemoAsset> created = fleet().seed(2, ownership, ownership.ownerId(), problem -> { });

        assertEquals(List.of("Skyfall Vampire", "Bayraktar TB2"),
                created.stream().map(DemoAsset::displayName).toList());
    }

    @Test
    void anAssetThatFailsToSimulateIsReportedAndTheRestStillGetCreated() {
        when(simulations.simulate(any(), any(), any()))
                .thenThrow(new IllegalStateException("simulated category not seeded"))
                .thenAnswer(invocation -> new SimulatedAsset(AssetId.random(), null));
        List<String> problems = new ArrayList<>();

        List<DemoAsset> created = fleet().seed(2, ownership, ownership.ownerId(), problems::add);

        assertEquals(1, created.size());
        assertEquals(List.of("asset FPV Pis-UN: simulated category not seeded"), problems);
    }

    @Test
    void startStreamsStartsOnlyTheRequestedPrefixAndReportsFailures() {
        List<DemoAsset> created = fleet().seed(3, ownership, ownership.ownerId(), problem -> { });
        when(assets.startStream(any(), any(), any()))
                .thenThrow(new IllegalStateException("publisher unreachable"))
                .thenReturn(null);
        List<String> problems = new ArrayList<>();

        int started = fleet().startStreams(created, 2, problems::add);

        assertEquals(1, started);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("publisher unreachable"), problems.toString());
        verify(assets, times(2)).startStream(any(), any(), any());
    }

    @Test
    void startingMoreStreamsThanThereAreAssetsIsNotAnError() {
        List<DemoAsset> created = fleet().seed(1, ownership, ownership.ownerId(), problem -> { });

        assertEquals(1, fleet().startStreams(created, 5, problem -> { }));
    }

    private DemoFleet fleet() {
        return new DemoFleet(simulations, assets, new DemoVideoLibrary(videoFolder));
    }

    private AssetSummary summary(String displayName) {
        return new AssetSummary(new Asset(AssetId.random(), displayName, new CategoryId("simulated"), ownership,
                Set.of(DeviceId.random()), Map.of(), LifecycleState.ACTIVE), "Simulated", AssetStatus.OFFLINE,
                null, null);
    }
}
