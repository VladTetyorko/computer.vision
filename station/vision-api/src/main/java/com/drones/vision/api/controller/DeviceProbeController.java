package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ProbeDeviceRequest;
import com.drones.vision.api.dto.ProbeDeviceResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.perception.application.device.ProbeResult;
import com.drones.vision.perception.application.device.ProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import com.drones.vision.api.support.SnapshotJpegEncoder;

/**
 * Driving REST adapter for CONTRACT 1's test-before-save connection probe (docs/plans/done/UX-REWORK-PLAN.md
 * §U-d item 3, UX-DESIGN.md §5.1): {@code POST /api/devices/probe} tries a connection and returns
 * what it found, without ever registering a device or asset.
 *
 * <p>Constructor-injected with {@link ProbeService} only — a genuine driving use-case interface,
 * not a raw driven port, per the house convention (see {@code .claude/skills/java-clean-code/SKILL.md}
 * §1). Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain}/{@code vision-application} — never on an adapter — so the
 * JPEG encode reuses the existing {@link SnapshotJpegEncoder} (the same one {@link
 * StreamController#snapshot} already uses) rather than a new one.
 *
 * <h2>Status codes</h2>
 * A malformed request (blank/missing {@code protocol}/{@code uri}, or a protocol <em>neither</em> a
 * video nor a telemetry adapter claims — {@link
 * com.drones.vision.perception.application.stream.UnsupportedProtocolException}) maps to {@code 400}; a claimed
 * protocol whose connection attempt itself fails, times out, or ends without a frame ({@link
 * com.drones.vision.perception.application.device.ProbeFailedException}) maps to {@code 422} — both via {@link
 * ApiExceptionHandler}. Success is always {@code 200}, never {@code 201}: nothing is created.
 *
 * <p>A {@code 200} carries one of {@link ProbeDeviceResponse}'s two shapes. A telemetry-only link
 * ({@code mavlink}) proves itself with a sample instead of a frame, so there is nothing to JPEG-encode
 * — the encoder is skipped rather than handed a {@code null}
 * (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §3).
 */
@RestController
public class DeviceProbeController {

    private final ProbeService probeService;
    /**
     * Constructor-injected (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D) — {@code vision-app} now supplies
     * this as a real bean, mapped from its own Spring {@code VisionApiProperties} record; the same
     * instance {@link StreamController#snapshot} uses.
     */
    private final SnapshotJpegEncoder snapshotJpegEncoder;

    public DeviceProbeController(ProbeService probeService, SnapshotJpegEncoder snapshotJpegEncoder) {
        this.probeService = Objects.requireNonNull(probeService, "probeService must not be null");
        this.snapshotJpegEncoder = Objects.requireNonNull(snapshotJpegEncoder, "snapshotJpegEncoder must not be null");
    }

    /**
     * Tests a connection and returns a decoded frame plus what could be learned about it —
     * CONTRACT 1's pinned response shape.
     *
     * @param request the connection to test
     * @return the probe's result
     */
    @PostMapping("/api/devices/probe")
    @OpenByDesign(reason = "operates on a caller-supplied connection descriptor (protocol+uri) that "
            + "names no existing device or asset -- nothing is created, and nothing already in "
            + "anyone's fleet is read (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding "
            + "A2). There is no Ownership/AssetId anywhere in the request or ProbeDeviceResponse to "
            + "scope against; a caller still needs its own credentials to reach whatever protocol+uri "
            + "it names, exactly as OnboardingController#probeCandidate's identical shape does.")
    public ProbeDeviceResponse probe(@RequestBody ProbeDeviceRequest request) {
        ProbeResult result = probeService.probe(request.toDescriptor());
        // A telemetry-only link has no frame to encode -- see ProbeDeviceResponse's "two legal shapes".
        byte[] jpeg = result.telemetryOnly() ? null : snapshotJpegEncoder.encode(result.frame());
        return ProbeDeviceResponse.from(result, jpeg);
    }
}
