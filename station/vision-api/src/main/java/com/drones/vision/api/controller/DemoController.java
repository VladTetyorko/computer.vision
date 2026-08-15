package com.drones.vision.api.controller;

import com.drones.vision.api.demo.DemoPlan;
import com.drones.vision.api.demo.DemoScenario;
import com.drones.vision.api.demo.DemoVideoLibrary;
import com.drones.vision.api.dto.DemoSeedRequest;
import com.drones.vision.api.dto.DemoSeedResponse;
import com.drones.vision.api.dto.DemoStatusResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Driving REST adapter for the demo data package: one button press fills an empty platform with a
 * fleet, a roster, assignments, geofences and marks.
 *
 * <p><strong>Gated by {@code vision.demo.enabled} (default on).</strong> With it set to {@code
 * false} this controller and every {@code …api.demo} bean are absent from the context entirely, so
 * both routes 404 like any unmapped path — the same "a feature flag removes the beans, it does not
 * branch inside them" shape {@code DatasetController}/{@code LiveController} already use. Turn it
 * off anywhere that is not local development: seeding is unauthenticated when {@code
 * vision.auth.enabled=false}, and the accounts it creates share one well-known password.
 *
 * <h2>Who the data belongs to</h2>
 * {@link DemoScenario} resolves the acting user from {@code CurrentUser}; this controller threads no
 * principal of its own, mirroring {@code SimulationController}.
 *
 * <h2>Status codes</h2>
 * Seeding is deliberately fault-tolerant rather than atomic: a step that fails lands in the
 * response's {@code problems} list, not in an error status. A 500 from here therefore means
 * something outside the seeded steps broke, not "one asset could not be created".
 */
@RestController
@ConditionalOnProperty(prefix = "vision.demo", name = "enabled", matchIfMissing = true)
public class DemoController {

    private final DemoScenario scenario;
    private final DemoVideoLibrary videos;

    public DemoController(DemoScenario scenario, DemoVideoLibrary videos) {
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
        this.videos = Objects.requireNonNull(videos, "videos must not be null");
    }

    /**
     * Whether the demo package is available, and what footage it would use.
     *
     * @return the video folder and the playable files directly inside it
     */
    @GetMapping("/api/demo")
    public DemoStatusResponse status() {
        return new DemoStatusResponse(true, videos.directory().toString(),
                videos.videos().stream().map(Path::getFileName).map(Path::toString).toList());
    }

    /**
     * Fills the platform with demo data.
     *
     * <p>Synchronous, and slow in proportion to {@link DemoSeedRequest#startStreams()} — each
     * started stream opens a real video source.
     *
     * @param request how much to create; the whole body may be absent for {@link DemoPlan#DEFAULT}
     * @return what was created, plus one line per step that failed
     */
    @PostMapping("/api/demo/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoSeedResponse seed(@RequestBody(required = false) DemoSeedRequest request) {
        DemoPlan plan = request == null ? DemoPlan.DEFAULT : request.toPlan();
        return DemoSeedResponse.of(scenario.seed(plan));
    }
}
