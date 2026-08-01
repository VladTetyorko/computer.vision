package com.drones.vision.app;

import com.drones.vision.adapter.persistence.FilesystemDatasetExport;
import com.drones.vision.api.DatasetController;
import com.drones.vision.api.LabelingController;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.LabelingService;
import com.drones.vision.application.TrainingStores;
import com.drones.vision.domain.port.out.DatasetExportPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for the <em>default</em> {@code vision.training.*} configuration (no override, per
 * {@link VisionTrainingProperties#enabled()}'s default of {@code false} — docs/CV-TRAINING-PLAN.md
 * §3/§G, Wave T4): asserts the context still loads cleanly with every training bean/controller
 * entirely absent — {@code GET/POST /api/datasets}[/{id}], {@code POST
 * /api/streams/{id}/samples}, {@code GET /api/samples/{id}/image}, {@code PUT
 * /api/samples/{id}/annotations}, and {@code /api/datasets/{id}/export}* all 404 like any other
 * unmapped route, exactly as before this feature existed — the guardrail docs/CV-TRAINING-PLAN.md
 * §G names explicitly. See {@link TrainingEnabledWiringTest} for the opposite (flag-on) counterpart.
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
    }

    @Test
    void noDatasetExportPortBeanExistsByDefault() {
        assertTrue(applicationContext.getBeansOfType(DatasetExportPort.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(FilesystemDatasetExport.class).isEmpty());
    }
}
