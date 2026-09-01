package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcDatasetUploadPort;
import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcTrainingPort;
import com.drones.vision.api.controller.DatasetController;
import com.drones.vision.api.controller.LabelingController;
import com.drones.vision.api.controller.ModelRegistryController;
import com.drones.vision.api.controller.TrainingJobController;
import com.drones.vision.learning.application.DatasetService;
import com.drones.vision.learning.application.DefaultDatasetService;
import com.drones.vision.learning.application.DefaultLabelingService;
import com.drones.vision.learning.application.DefaultModelRegistryService;
import com.drones.vision.learning.application.DefaultTrainingJobService;
import com.drones.vision.learning.application.LabelingService;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.events.application.ReplaySources;
import com.drones.vision.learning.application.TrainingJobService;
import com.drones.vision.learning.application.TrainingStores;
import com.drones.vision.learning.domain.port.DatasetUploadPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.learning.domain.port.TrainingPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Context test for {@code vision.training.enabled=true} (docs/plans/done/CV-TRAINING-PLAN.md §3/Wave T4, as
 * delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §7): asserts {@link TrainingWiringConfiguration} wires the
 * real {@link DatasetService}/{@link LabelingService}/{@link DatasetUploadPort} (not absent, as
 * they are by default — see {@link TrainingDisabledWiringTest}), and that {@code
 * DatasetController}/{@code LabelingController} (vision-api, component-scanned) both resolve.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest} — this also means {@link #replaySources} below resolves
 * against {@link WiringConfiguration#replayFrameExtractionPort}'s no-op branch (the real mediamtx
 * one is exercised by the docker-gated adapter-publish-hls suite, not a context test).
 *
 * <p>Also covers the model registry control plane this class's own {@link
 * TrainingWiringConfiguration} gained (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9): with {@code
 * vision.cv.enabled} left at its default {@code false} (detection stays off), {@code
 * vision.training.enabled=true} alone is enough to build {@link WiringConfiguration#cvGrpcChannel}
 * (its {@code @ConditionalOnExpression} matches on training alone) and resolve {@code
 * trainingPort}/{@code trainingJobService}/{@code TrainingJobController} — proving a training-only
 * deployment doesn't need detection wired too. See {@link CvAndTrainingSharedChannelWiringTest} for
 * the both-enabled case that actually proves channel <em>sharing</em>.
 *
 * <p><b>{@code modelRegistryPort}/{@code modelRegistryService}/{@code ModelRegistryController} are
 * a separate switch</b> since docs/plans/active/CV-SETTINGS-PLAN.md §5 (CV-SETTINGS-CONTEXT.md's
 * W4-app → W5 handoff decoupled the registry from training specifically): {@code
 * vision.cv.registry.enabled}'s own {@code application.yaml} default follows {@code
 * vision.cv.enabled}, not {@code vision.training.enabled}, so a training-only deployment
 * ({@code vision.cv.enabled} left unset) no longer gets the registry for free — this class sets
 * {@code vision.cv.registry.enabled=true} explicitly to keep exercising it. See {@link
 * CvEnabledWiringTest#cvOnlyConfigurationWiresTheModelRegistryByDefault} for the mirror case (CV
 * on, training off) and {@link CvRegistryExplicitOptOutWiringTest} for the opt-out.
 *
 * <p>And the training-job flow (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2's last backend wave, folded
 * with upload by docs/plans/done/CV-TRAINING-V2-PLAN.md §4): {@code trainingPort}/{@code trainingJobService}/
 * {@code TrainingJobController} all resolve on the same {@code vision.training.enabled=true}
 * switch, {@code trainingPort} sharing the identical {@link WiringConfiguration#cvGrpcChannel} bean
 * {@code modelRegistryPort} already does.
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.training.enabled=true",
        "vision.cv.registry.enabled=true"})
class TrainingEnabledWiringTest {

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private LabelingService labelingService;

    @Autowired
    private DatasetUploadPort datasetUploadPort;

    @Autowired
    private TrainingStores trainingStores;

    @Autowired
    private ReplaySources replaySources;

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
    void datasetUploadPortResolvesToTheGrpcImplementationOverTheSharedChannel() {
        assertInstanceOf(GrpcDatasetUploadPort.class, datasetUploadPort);
        assertNotNull(cvGrpcChannel);
    }

    @Test
    void trainingStoresBundlesTheFourWaveT1Ports() {
        assertNotNull(trainingStores);
    }

    @Test
    void replaySourcesBundlesTheThreeReplayCollaborators() {
        assertNotNull(replaySources);
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
