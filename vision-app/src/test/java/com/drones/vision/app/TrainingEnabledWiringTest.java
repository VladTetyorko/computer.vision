package com.drones.vision.app;

import com.drones.vision.adapter.persistence.FilesystemDatasetExport;
import com.drones.vision.api.DatasetController;
import com.drones.vision.api.LabelingController;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.DefaultDatasetService;
import com.drones.vision.application.DefaultLabelingService;
import com.drones.vision.application.LabelingService;
import com.drones.vision.application.TrainingStores;
import com.drones.vision.domain.port.out.DatasetExportPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for {@code vision.training.enabled=true} (docs/CV-TRAINING-PLAN.md §3/Wave T4):
 * asserts {@link TrainingWiringConfiguration} wires the real {@link DatasetService}/{@link
 * LabelingService}/{@link DatasetExportPort} (not absent, as they are by default — see {@link
 * TrainingDisabledWiringTest}), and that {@code DatasetController}/{@code LabelingController}
 * (vision-api, component-scanned) both resolve.
 *
 * <p>{@code vision.training.export-dir} is redirected to a fresh {@code @TempDir} via {@link
 * DynamicPropertySource} — same "start the property source first, read it before context refresh"
 * idiom {@link RtspSimulationDockerE2ETest} already uses for its mediamtx port — so this test never
 * writes into the real default {@code data/training-exports} relative path (harmless either way,
 * since {@link FilesystemDatasetExport}'s constructor does no I/O of its own, but a dedicated temp
 * directory keeps this test's on-disk footprint honestly empty).
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.training.enabled=true"})
class TrainingEnabledWiringTest {

    @TempDir
    static Path exportDir;

    @DynamicPropertySource
    static void trainingProperties(DynamicPropertyRegistry registry) {
        registry.add("vision.training.export-dir", () -> exportDir.toString());
    }

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private LabelingService labelingService;

    @Autowired
    private DatasetExportPort datasetExportPort;

    @Autowired
    private TrainingStores trainingStores;

    @Autowired
    private DatasetController datasetController;

    @Autowired
    private LabelingController labelingController;

    @Test
    void datasetServiceResolvesToTheRealImplementation() {
        assertInstanceOf(DefaultDatasetService.class, datasetService);
    }

    @Test
    void labelingServiceResolvesToTheRealImplementation() {
        assertInstanceOf(DefaultLabelingService.class, labelingService);
    }

    @Test
    void datasetExportPortResolvesToTheFilesystemImplementationRootedAtTheConfiguredDirectory() {
        assertInstanceOf(FilesystemDatasetExport.class, datasetExportPort);
    }

    @Test
    void trainingStoresBundlesTheFourWaveT1Ports() {
        assertNotNull(trainingStores);
    }

    @Test
    void datasetAndLabelingControllersBothResolve() {
        assertNotNull(datasetController);
        assertNotNull(labelingController);
    }
}
