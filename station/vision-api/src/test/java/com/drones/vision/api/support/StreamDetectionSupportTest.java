package com.drones.vision.api.support;

import com.drones.vision.api.dto.StartStreamRequest;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.application.profile.EffectiveProfile;
import com.drones.vision.perception.application.profile.KnobSources;
import com.drones.vision.perception.application.profile.ProfileSource;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link StreamDetectionSupport#resolveStartConfig}: the one place an asset-tier {@code CvProfile}
 * fold (potentially itself seeded by an {@code intent}, docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.7) and a session-tier {@link StartStreamRequest#mergeOnto} override compose — item 2(a)'s
 * "asset-tier intent plus a session-tier expert override coexist in the resolved config" half that
 * cannot be exercised inside {@code contexts/vision-perception}'s {@code CvProfileResolverTest}:
 * {@link StartStreamRequest}/{@code mergeOnto} live in this module (an adapter), and {@code
 * vision-perception} (a context) may never depend on one, so this is the lowest layer where both
 * collaborators can appear in the same test.
 */
class StreamDetectionSupportTest {

    private final AssetRepositoryPort assetRepositoryPort = mock(AssetRepositoryPort.class);
    private final CvProfileService cvProfileService = mock(CvProfileService.class);
    private final CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
    private final PipelineConfig platformDefault = PipelineConfig.defaults();

    private final StreamDetectionSupport support = new StreamDetectionSupport(platformDefault, null,
            cvProfileService, assetRepositoryPort, currentUser, null);

    private static Asset asset(AssetId assetId, DeviceId deviceId) {
        Instant now = Instant.now();
        return new Asset(assetId, "Asset under test", new CategoryId("simulated"), new Ownership(UserId.random(),
                GroupId.random()), Set.of(deviceId), Map.of(), LifecycleState.ACTIVE, Identity.NONE, Custody.NONE,
                InventoryState.IN_STOCK, now, now);
    }

    @Test
    void intentSeededAssetTierProfileAndAnExplicitSessionOverrideBothLandInTheResolvedConfig() {
        DeviceId deviceId = DeviceId.random();
        AssetId assetId = AssetId.random();
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(asset(assetId, deviceId)));

        // Stands in for an asset-tier CvProfile whose model was itself seeded by IntentPolicyResolver
        // (Intent.PEOPLE) at profile-creation time -- resolveStartConfig only ever sees the already-
        // folded PipelineConfig, never the intent that produced it, so a distinctive model id is
        // enough to prove this layer's contribution survives the merge below.
        PipelineConfig intentSeededAssetConfig = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.40, 10, 2,
                Set.of("person"), EventRuleConfig.defaults(), true, TrackingConfig.defaults(), Set.of(), false);
        EffectiveProfile effective = new EffectiveProfile(assetId, CvProfileId.random(), "People (asset)",
                ProfileSource.ASSET, intentSeededAssetConfig, KnobSources.platform(), null);
        when(cvProfileService.effective(any(), any(), any(), any())).thenReturn(effective);

        // The session-tier "expert override" layer: this operator pins a stricter confidence
        // threshold for just this stream, touching a DIFFERENT knob than the one the profile's
        // intent seeded (model/labelFilter) -- if both layers' contributions are present in the
        // result, they coexist rather than one clobbering the other.
        StartStreamRequest sessionOverride = new StartStreamRequest(0.85, null, null, null, null, null);

        PipelineConfig resolved = support.resolveStartConfig(deviceId, sessionOverride);

        assertEquals("yolo26n.pt", resolved.model().id(), "asset-tier (intent-seeded) model must survive the merge");
        assertEquals(Set.of("person"), resolved.labelFilter(), "asset-tier (intent-seeded) labelFilter must survive");
        assertEquals(0.85, resolved.confidenceThreshold(), "session-tier override must win for the field it named");
    }

    @Test
    void anUnownedDeviceSkipsProfileResolutionAndMergesStraightOntoThePlatformDefault() {
        DeviceId deviceId = DeviceId.random();
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.empty());

        StartStreamRequest sessionOverride = new StartStreamRequest(0.85, null, null, null, null, null);

        PipelineConfig resolved = support.resolveStartConfig(deviceId, sessionOverride);

        assertEquals(platformDefault.model().id(), resolved.model().id());
        assertEquals(0.85, resolved.confidenceThreshold());
    }
}
