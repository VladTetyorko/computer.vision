package com.drones.vision.api.controller;

import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.api.dto.CvModelsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import com.drones.vision.api.proxy.HlsProxyController;

/**
 * Driving REST adapter for the detection-model roster (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire
 * contract) — the picker the Fly cockpit's CV control panel builds its model dropdown from.
 *
 * <p>Constructor-injected with the roster itself, a plain {@code List<CvModelResponse>} bean rather
 * than a use-case port — the same "raw collaborator, not a domain port" exception {@link
 * HlsProxyController} already documents for {@code hlsProxyUpstreamBase}. This is deliberate
 * (docs/plans/done/CV-CONTROL-PLAN.md §D): the roster is a static, config-backed list wired in {@code
 * vision-app} next to its other {@code vision.cv.*} wiring, <strong>not</strong> the dormant {@code
 * ModelRegistryPort} (which models versioned promote/rollback, a Phase-3 training-studio concern
 * with no implementation) and not a new {@code cv-service} roster RPC. A future real source, if one
 * is ever needed, is either that port or a small {@code cv-service} roster RPC (its {@code
 * ModelRegistry} already knows its own local roster) — a documented seam, not built now.
 *
 * <p>This endpoint never errors — the roster bean always has at least the built-in models.
 */
@RestController
public class CvModelsController {

    private final List<CvModelResponse> cvModelRoster;

    public CvModelsController(List<CvModelResponse> cvModelRoster) {
        this.cvModelRoster =
                List.copyOf(Objects.requireNonNull(cvModelRoster, "cvModelRoster must not be null"));
    }

    /**
     * The detection-model roster, in display order ({@code yolo26n.pt} first — the default).
     *
     * @return the roster wrapped per the frozen wire contract
     */
    @OpenByDesign(reason = "Reference list of CV models the service can load — a capability list, not fleet data.")
    @GetMapping("/api/cv/models")
    public CvModelsResponse models() {
        return new CvModelsResponse(cvModelRoster);
    }
}
