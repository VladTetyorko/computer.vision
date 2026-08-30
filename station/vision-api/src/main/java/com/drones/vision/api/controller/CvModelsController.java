package com.drones.vision.api.controller;

import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.api.dto.CvModelsResponse;
import com.drones.vision.learning.application.CatalogSource;
import com.drones.vision.learning.application.ConfigModelCatalog;
import com.drones.vision.learning.application.CvModelCatalog;
import com.drones.vision.learning.application.CvModelView;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.domain.model.ModelAvailability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.proxy.HlsProxyController;

/**
 * Driving REST adapter for the detection-model roster (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire
 * contract, widened by docs/plans/active/CV-SETTINGS-PLAN.md §5.2 for the {@code /vision/profiles} model
 * picker) — the picker the Fly cockpit's CV control panel, and now the profile editor, build their
 * model dropdown from.
 *
 * <p><strong>Not the same roster as {@link ModelRegistryController}</strong>: that controller is the
 * dynamic promote/rollback control plane, gated behind {@code vision.cv.registry.enabled} and absent
 * entirely when off; this one is unconditional and never errors (docs/plans/active/CV-SETTINGS-PLAN.md
 * §8 OQ5) — the same contract this endpoint has always had (docs/plans/done/CV-CONTROL-PLAN.md §D).
 *
 * <p>Constructor-injected with an {@link ObjectProvider}&lt;{@link ModelRegistryService}&gt; rather
 * than the service directly, so this controller stays present and functional even when the registry
 * flag is off: when the service bean exists, {@link ModelRegistryService#models()} is called directly
 * (it never throws by its own contract, falling back to its own {@link ConfigModelCatalog} internally
 * if the connected worker is unreachable); when the service bean is entirely absent (the flag off),
 * this controller builds the exact same {@link CatalogSource#CONFIG} shape itself from {@link
 * #configModelCatalog}, so a caller sees byte-identical behaviour whether the registry is "off" or
 * merely "on but the worker happens to be unreachable right now."
 */
@RestController
public class CvModelsController {

    private final ObjectProvider<ModelRegistryService> modelRegistryService;
    private final ConfigModelCatalog configModelCatalog;

    public CvModelsController(ObjectProvider<ModelRegistryService> modelRegistryService,
                               ConfigModelCatalog configModelCatalog) {
        this.modelRegistryService =
                Objects.requireNonNull(modelRegistryService, "modelRegistryService must not be null");
        this.configModelCatalog = Objects.requireNonNull(configModelCatalog, "configModelCatalog must not be null");
    }

    /**
     * The detection-model roster, in display order ({@code yolo26n.pt} first — the default).
     *
     * @return the roster wrapped per the frozen wire contract; never throws
     */
    @OpenByDesign(reason = "Reference list of CV models the service can load — a capability list, not fleet data.")
    @GetMapping("/api/cv/models")
    public CvModelsResponse models() {
        ModelRegistryService service = modelRegistryService.getIfAvailable();
        CvModelCatalog catalog = service != null ? service.models()
                : new CvModelCatalog(configModelCatalog.models().stream()
                        .map(record -> CvModelView.of(record, ModelAvailability.PRESENT)).toList(),
                        CatalogSource.CONFIG);
        boolean registrySource = catalog.source() == CatalogSource.REGISTRY;
        return new CvModelsResponse(catalog.models().stream().map(view -> CvModelResponse.from(view, registrySource))
                .toList());
    }
}
