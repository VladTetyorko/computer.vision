package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SubsystemStatusResponse;
import com.drones.vision.api.dto.SystemStatusResponse;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Driving REST adapter for {@code GET /api/system/status} (docs/plans/active/SYSTEM-STATUS-PLAN.md
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
 * <p><strong>One sick provider must not blind the page</strong>: {@link #status()} catches any
 * exception a single {@link SubsystemStatusPort} throws, reporting that one entry as {@link
 * Health#UNKNOWN} with the exception's message as {@code detail} rather than failing the whole
 * request — a provider that cannot determine its own subsystem's health must never take down the
 * status page for every other subsystem.
 */
@RestController
public class SystemStatusController {

    /**
     * Worst-to-best ranking used by {@link #overall(List)}, deliberately excluding {@link
     * Health#DISABLED} (never a candidate — filtered out before this map is consulted). The plan
     * (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.3) specifies "worst health across subsystems" but
     * not the exact DOWN-vs-UNKNOWN precedence; this class's call is that a <em>confirmed</em> DOWN
     * outranks an <em>inconclusive</em> UNKNOWN, since a provider that cannot say what is going on is
     * less alarming than one that has confirmed a real outage.
     */
    private static final Map<Health, Integer> SEVERITY = new EnumMap<>(Health.class);

    static {
        SEVERITY.put(Health.OK, 0);
        SEVERITY.put(Health.DEGRADED, 1);
        SEVERITY.put(Health.UNKNOWN, 2);
        SEVERITY.put(Health.DOWN, 3);
    }

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
        List<SubsystemStatus> statuses = providers.stream().map(this::safeStatus).toList();
        List<SubsystemStatusResponse> subsystems = statuses.stream().map(SubsystemStatusResponse::from).toList();
        return new SystemStatusResponse(overall(statuses), Instant.now(), subsystems);
    }

    private SubsystemStatus safeStatus(SubsystemStatusPort provider) {
        try {
            return provider.status();
        } catch (RuntimeException e) {
            String id = provider.getClass().getSimpleName();
            // Blank, not merely null: SubsystemStatus rejects a blank `detail`, so a provider that
            // threw with an empty message would make *this* constructor throw from inside the catch
            // block -- 500-ing the whole endpoint and defeating the very isolation it implements.
            // Throwable#toString() is blank-proof (it always carries at least the class name).
            String message = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            return new SubsystemStatus(id, id, Health.UNKNOWN, message, null, null);
        }
    }

    /**
     * The worst {@link Health} across {@code statuses}, ignoring {@link Health#DISABLED} — a
     * deliberately switched-off subsystem must never read as a fault. {@link Health#UNKNOWN} when
     * nothing remains after that filter, whether because {@code statuses} was empty to begin with or
     * because every subsystem present happens to be disabled.
     */
    private Health overall(List<SubsystemStatus> statuses) {
        return statuses.stream()
                .map(SubsystemStatus::health)
                .filter(health -> health != Health.DISABLED)
                .max(Comparator.comparingInt(SEVERITY::get))
                .orElse(Health.UNKNOWN);
    }
}
