package com.drones.vision.api;

import com.drones.vision.api.dto.ProbeDeviceRequest;
import com.drones.vision.api.dto.ProbeDeviceResponse;
import com.drones.vision.application.ProbeResult;
import com.drones.vision.application.ProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for CONTRACT 1's test-before-save connection probe (docs/UX-REWORK-PLAN.md
 * §U-d item 3, UX-DESIGN.md §5.1): {@code POST /api/devices/probe} tries a connection and returns
 * what it found, without ever registering a device or asset.
 *
 * <p>Constructor-injected with {@link ProbeService} only — a genuine driving use-case interface,
 * not a raw driven port, per the house convention (see {@code .claude/skills/java-clean-code/SKILL.md}
 * §1). Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain}/{@code vision-application} — never on an adapter — so the
 * JPEG encode reuses the existing package-private {@link SnapshotJpegEncoder} (the same one
 * {@link StreamController#snapshot} already uses) rather than a new one.
 *
 * <h2>Status codes</h2>
 * A malformed request (blank/missing {@code protocol}/{@code uri}, an unparseable {@code uri}, or
 * an unrecognized protocol — {@link com.drones.vision.application.UnsupportedProtocolException})
 * maps to {@code 400}; a recognized protocol whose connection attempt itself fails, times out, or
 * ends without a frame ({@link com.drones.vision.application.ProbeFailedException}) maps to {@code
 * 422} — both via {@link ApiExceptionHandler}. Success is always {@code 200}, never {@code 201}:
 * nothing is created.
 */
@RestController
public class DeviceProbeController {

    private final ProbeService probeService;

    public DeviceProbeController(ProbeService probeService) {
        this.probeService = Objects.requireNonNull(probeService, "probeService must not be null");
    }

    /**
     * Tests a connection and returns a decoded frame plus what could be learned about it —
     * CONTRACT 1's pinned response shape.
     *
     * @param request the connection to test
     * @return the probe's result
     */
    @PostMapping("/api/devices/probe")
    public ProbeDeviceResponse probe(@RequestBody ProbeDeviceRequest request) {
        ProbeResult result = probeService.probe(request.toDescriptor());
        byte[] jpeg = SnapshotJpegEncoder.encode(result.frame());
        return ProbeDeviceResponse.from(result, jpeg);
    }
}
