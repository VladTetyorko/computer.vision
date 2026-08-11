package com.drones.vision.app;

import com.drones.vision.api.controller.DatasetController;
import com.drones.vision.api.controller.LabelingController;
import com.drones.vision.api.controller.ModelRegistryController;
import com.drones.vision.api.controller.TrainingJobController;
import com.drones.vision.application.training.DatasetService;
import com.drones.vision.application.training.LabelingService;
import com.drones.vision.application.training.ModelRegistryService;
import com.drones.vision.application.replay.ReplaySources;
import com.drones.vision.application.training.TrainingJobService;
import com.drones.vision.application.training.TrainingStores;
import com.drones.vision.learning.domain.port.DatasetUploadPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.learning.domain.port.TrainingPort;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for the <em>default</em> {@code vision.training.*} configuration (no override, per
 * {@link VisionTrainingProperties#enabled()}'s default of {@code false} — docs/plans/done/CV-TRAINING-PLAN.md
 * §3/§G, Wave T4, as delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §7): asserts the context still loads
 * cleanly with every training bean/controller entirely absent — {@code GET/POST /api/datasets}[/{id}],
 * {@code POST /api/streams/{id}/samples}, {@code POST /api/usages/{id}/samples}, {@code GET
 * /api/samples/{id}/image}, {@code PUT /api/samples/{id}/annotations}, and {@code POST
 * /api/datasets/{id}/train} all 404 like any other unmapped route, exactly as before this feature
 * existed — the guardrail docs/plans/done/CV-TRAINING-PLAN.md §G names explicitly. See {@link
 * TrainingEnabledWiringTest} for the opposite (flag-on) counterpart.
 *
 * <p>Looks up every bean via {@link ApplicationContext#getBeansOfType} rather than {@code
 * @Autowired}, mirroring {@link LiveDisabledWiringTest}/{@link DiscoveryDisabledWiringTest}'s own
 * reasoning: a plain {@code @Autowired} field is required by default and would fail the context
 * entirely if the bean is genuinely absent, defeating the point of this test.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * CvWiringTest}/{@link AssetWiringTest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class TrainingDisabledWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void noTrainingControllersExistByDefault() {
        assertTrue(applicationContext.getBeansOfType(DatasetController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(LabelingController.class).isEmpty());
    }

    @Test
    void noTrainingServiceBeansExistByDefault() {
        assertTrue(applicationContext.getBeansOfType(DatasetService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(LabelingService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(TrainingStores.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ReplaySources.class).isEmpty());
    }

    @Test
    void noDatasetUploadPortBeanExistsByDefault() {
        assertTrue(applicationContext.getBeansOfType(DatasetUploadPort.class).isEmpty());
    }

    /**
     * docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9: with both {@code vision.cv.enabled} and {@code
     * vision.training.enabled} at their default {@code false}, the model registry controller/
     * service/port are all absent too — same "absent entirely" guardrail as the other three tests
     * above, extended to the new beans this task added.
     */
    @Test
    void noModelRegistryBeansExistByDefault() {
        assertTrue(applicationContext.getBeansOfType(ModelRegistryController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ModelRegistryService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(ModelRegistryPort.class).isEmpty());
    }

    /**
     * docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2's last backend wave: the training-job flow's
     * beans/controller are absent by default too, same "absent entirely" guardrail as {@link
     * #noModelRegistryBeansExistByDefault()} above.
     */
    @Test
    void noTrainingJobBeansExistByDefault() {
        assertTrue(applicationContext.getBeansOfType(TrainingJobController.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(TrainingJobService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(TrainingPort.class).isEmpty());
    }

    /**
     * The opt-in guardrail at its most literal: with neither {@code vision.cv.enabled} nor {@code
     * vision.training.enabled} set, {@link WiringConfiguration#cvGrpcChannel} — the bean shared by
     * {@code GrpcDetectionPort} and {@code GrpcModelRegistryPort} — isn't built at all, so this
     * task adds no gRPC channel/executor overhead to the default-config context beyond what
     * existed before it.
     */
    @Test
    void noSharedCvGrpcChannelBeanExistsByDefault() {
        assertTrue(applicationContext.getBeansOfType(ManagedChannel.class).isEmpty());
    }
}
