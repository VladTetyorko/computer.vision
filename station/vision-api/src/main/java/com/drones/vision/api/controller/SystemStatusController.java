package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SystemStatusResponse;
import com.drones.vision.api.support.SystemStatusReader;
import com.drones.vision.platform.SubsystemStatusPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for {@code GET /api/system/status} (docs/plans/done/SYSTEM-STATUS-PLAN.md
 * §4.3) — UX-DESIGN §7.2's "honest status over optimistic status" doctrine: whether each subsystem
 * this deployment depends on (cv-service, the MAVLink link, video publish, live SSE updates) is
 * currently working, so an operator whose CV has died can see why instead of guessing from a frozen
 * video tile.
 *
 * <p>Depends only on {@code vision-platform}'s {@link SubsystemStatusPort}, never on an adapter
 * directly — the dependency rule forbids {@code vision-api} depending on {@code cv/grpc}, {@code
 * drone-link/mavlink}, or {@code video-output/publish-hls}, so {@code vision-app} collects the
 * {@code List<SubsystemStatusPort>} from those adapters (plus {@code vision-api}'s own {@code
 * live-updates} provider, found by component scan) and hands it here as one ordinary constructor
 * collaborator, the same "raw collaborator list" shape {@code CvModelsController} already uses for
 * its roster.
 *
 * <p><strong>No {@code managerOnly} restriction</strong> — a deliberate call, not an oversight: this
 * endpoint is readable by any authenticated user (or, with auth disabled, unconditionally). It
 * exposes no secrets, only subsystem ids/labels/states/hostnames already visible elsewhere (e.g. in
 * log output any operator with server access could read anyway), and gating it behind a role would
 * hide exactly the information a non-manager operator needs most when something has gone wrong.
 *
 * <p><strong>One sick provider must not blind the page</strong>: {@link SystemStatusReader#read}
 * catches any exception a single {@link SubsystemStatusPort} throws, reporting that one entry as
 * {@link com.drones.vision.platform.Health#UNKNOWN} with the exception's message as {@code detail}
 * rather than failing the whole request — a provider that cannot determine its own subsystem's
 * health must never take down the status page for every other subsystem.
 *
 * <p>The sampling/rollup logic itself lives in {@link SystemStatusReader} (docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4a) — extracted so {@code
 * com.drones.vision.api.live.SystemStatusSampler} can reuse the exact same rollup for the {@code
 * system} live topic's change detection. This controller's wire output is unchanged.
 */
@RestController
public class SystemStatusController {

    private final List<SubsystemStatusPort> providers;

    public SystemStatusController(List<SubsystemStatusPort> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
    }

    /**
     * Every registered subsystem's current status, plus the rolled-up {@code overall} verdict.
     *
     * @return the response body
     */
    @GetMapping("/api/system/status")
    public SystemStatusResponse status() {
        return SystemStatusReader.read(providers);
    }
}
