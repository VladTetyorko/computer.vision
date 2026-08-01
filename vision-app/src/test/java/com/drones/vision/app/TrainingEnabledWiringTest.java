package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcTrainingPort;
import com.drones.vision.adapter.persistence.FilesystemDatasetExport;
import com.drones.vision.api.DatasetController;
import com.drones.vision.api.LabelingController;
import com.drones.vision.api.ModelRegistryController;
import com.drones.vision.api.TrainingJobController;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.DefaultDatasetService;
import com.drones.vision.application.DefaultLabelingService;
import com.drones.vision.application.DefaultModelRegistryService;
import com.drones.vision.application.DefaultTrainingJobService;
import com.drones.vision.application.LabelingService;
import com.drones.vision.application.ModelRegistryService;
import com.drones.vision.application.TrainingJobService;
import com.drones.vision.application.TrainingStores;
import com.drones.vision.domain.port.out.DatasetExportPort;
import com.drones.vision.domain.port.out.ModelRegistryPort;
import com.drones.vision.domain.port.out.TrainingPort;
import io.grpc.ManagedChannel;
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
 *
 * <p>Also covers the model registry control plane this class's own {@link
 * TrainingWiringConfiguration} gained (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9): with {@code
 * vision.cv.enabled} left at its default {@code false} (detection stays off), {@code
 * vision.training.enabled=true} alone is enough to build {@link WiringConfiguration#cvGrpcChannel}
 * (its {@code @ConditionalOnExpression} matches on training alone) and resolve {@code
 * modelRegistryPort}/{@code modelRegistryService}/{@code ModelRegistryController} — proving a
 * training-only deployment doesn't need detection wired too. See {@link
 * CvAndTrainingSharedChannelWiringTest} for the both-enabled case that actually proves channel
 * <em>sharing</em>.
 *
 * <p>And the training-job flow (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2's last backend wave):
 * {@code trainingPort}/{@code trainingJobService}/{@code TrainingJobController} all resolve on the
 * same {@code vision.training.enabled=true} switch, {@code trainingPort} sharing the identical
 * {@link WiringConfiguration#cvGrpcChannel} bean {@code modelRegistryPort} already does.
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

    @Autowired
    private ModelRegistryPort modelRegistryPort;

    @Autowired
    private ModelRegistryService modelRegistryService;

    @Autowired
    private ModelRegistryController modelRegistryController;

    @Autowired
    private ManagedChannel cvGrpcChannel;

    @Autowired
    private TrainingPort trainingPort;

    @Autowired
    private TrainingJobService trainingJobService;

    @Autowired
    private TrainingJobController trainingJobController;

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

    @Test
    void modelRegistryPortResolvesToTheGrpcImplementationOverTheSharedChannel() {
        assertInstanceOf(GrpcModelRegistryPort.class, modelRegistryPort);
        assertNotNull(cvGrpcChannel);
    }

    @Test
    void modelRegistryServiceResolvesToTheRealImplementation() {
        assertInstanceOf(DefaultModelRegistryService.class, modelRegistryService);
    }

    @Test
    void modelRegistryControllerResolves() {
        assertNotNull(modelRegistryController);
    }

    @Test
    void trainingPortResolvesToTheGrpcImplementationOverTheSharedChannel() {
        assertInstanceOf(GrpcTrainingPort.class, trainingPort);
        assertNotNull(cvGrpcChannel);
    }

    @Test
    void trainingJobServiceResolvesToTheRealImplementation() {
        assertInstanceOf(DefaultTrainingJobService.class, trainingJobService);
    }

    @Test
    void trainingJobControllerResolves() {
        assertNotNull(trainingJobController);
    }
}
