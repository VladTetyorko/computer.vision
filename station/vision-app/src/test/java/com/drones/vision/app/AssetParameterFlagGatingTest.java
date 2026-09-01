package com.drones.vision.app;

import com.drones.vision.api.controller.AssetParameterController;
import com.drones.vision.api.dto.ParameterWriteRequest;
import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.perception.application.stream.AssetStreamService;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves docs/plans/active/FLEET-RADIO-PLAN.md R5's expected result #5: with {@code
 * vision.onboarding.probe.enabled} at its default ({@code false}), {@link AssetParameterController}
 * is wired (it is a plain {@code @RestController}, unconditional) but every write it dispatches is
 * refused, because {@code VehicleConfigPort} resolves to the no-op adapter regardless of what this
 * test does.
 *
 * <p>Reuses {@link SimStreamSmokeTest}'s exact {@code @SpringBootTest} properties and {@code
 * @Import} so this test shares that class's cached context rather than paying for a new connection
 * pool against a properties combination no other test class uses (station/vision-app/MODULE.md).
 *
 * <h2>Why a real stream, not a bare controller call</h2>
 * {@code DefaultRemediationService.writeParameter} checks disarmed-only <em>before</em> device
 * resolution. Calling the controller against a freshly-created asset with no telemetry would still
 * get refused, but by "arming state unknown" — a true statement, yet not proof that the flag itself
 * is what stands in the way. Starting a real {@code sim} stream and waiting for the first telemetry
 * sample is the only way to get a real, known-disarmed asset in this context (the synthetic flight
 * state forces {@code armed=false} for its first several ticks), so the refusal this test asserts on
 * is unambiguously {@code firstSupportedDevice}'s "no active device this platform can configure" —
 * the exact 409 every onboarding endpoint gives when probing is disabled, not a different one this
 * controller invented.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(SimStreamSmokeTest.RecordingPublisherConfig.class)
class AssetParameterFlagGatingTest {

    private static final Duration TELEMETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TELEMETRY_POLL_INTERVAL = Duration.ofMillis(200);

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetStreamService assetStreamService;

    @Autowired
    private AssetLiveStatePort assetLiveStatePort;

    @Autowired
    private AssetParameterController assetParameterController;

    @Test
    void writeParameterRefusesWithProbingDisabledEvenForAKnownDisarmedAsset() throws InterruptedException {
        DeviceRegistration videoDevice = new DeviceRegistration(
                "flag-gating-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://flag-gating-video"), Map.of("fps", "30")));
        DeviceRegistration telemetryDevice = new DeviceRegistration(
                "flag-gating-gps", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://flag-gating-telemetry"), Map.of()));

        Asset asset = assetService.create(new AssetSpec("Flag Gating Test Asset",
                new CategoryId("drone"), Map.of(), List.of(videoDevice, telemetryDevice)),
                DevPrincipal.OWNERSHIP, DevPrincipal.USER_ID);

        assetStreamService.startStream(asset.id(), null, PipelineConfig.defaults());
        try {
            awaitFirstTelemetrySample(asset.id());

            ParameterWriteRequest request = new ParameterWriteRequest("SR0_EXTRA1", 10.0, true);
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> assetParameterController.writeParameter(asset.id().value().toString(), request));
            assertTrue(thrown.getMessage().contains("no active device this platform can configure"),
                    () -> "expected the flag-off no-op-port refusal, got: " + thrown.getMessage());
        } finally {
            assetService.stopStream(asset.id());
        }
    }

    private void awaitFirstTelemetrySample(AssetId assetId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TELEMETRY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (assetLiveStatePort.latestTelemetry(assetId).isPresent()) {
                return;
            }
            Thread.sleep(TELEMETRY_POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("no telemetry sample arrived for " + assetId.value()
                + " within " + TELEMETRY_TIMEOUT + " of the sim telemetry source's 1Hz cadence");
    }
}
