package com.drones.vision.api.dto;

import com.drones.vision.api.dto.RemediationResultResponse.RemediationActionResponse;
import com.drones.vision.api.dto.VehicleProfileResponse.MessageObservationResponse;
import com.drones.vision.api.dto.VehicleProfileResponse.ParameterReadingResponse;
import com.drones.vision.api.dto.ReadinessReportResponse.FeatureReadinessResponse;
import com.drones.vision.flight.domain.model.FeatureReadiness;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.FlightPassport;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterDrift;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.flight.domain.model.ReadinessVerdict;
import com.drones.vision.flight.domain.model.RemedyKind;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.Asset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level proof that every onboarding DTO matches docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §8.1's frozen wire contract exactly — field names, nullability, and enum spellings — mirroring
 * {@link ManualControlFrameDtoTest}'s own "assert the literal string contains this exact substring"
 * idiom rather than a structural/reflective comparison, so a silent field rename or dropped {@code
 * null} fails this test even though it would not fail a `record`-equality check.
 */
class OnboardingWireContractTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void vehicleProfileResponseMatchesTheFrozenShapeIncludingExplicitNulls() {
        VehicleProfile profile = new VehicleProfile(
                "udp://0.0.0.0:14550#7",
                Instant.parse("2026-08-17T10:04:11Z"),
                7,
                "ardupilot",
                null, // firmwareVersion unanswered
                null, // vehicleKind unknown
                null, // capabilityBitmask unanswered
                List.of(),
                List.of(new MessageObservation(33, "GLOBAL_POSITION_INT", 0.9, 9)),
                List.of(new ParameterReading("SR2_EXTRA2", 0.0, "REAL32")),
                null, // linkBytesPerSecond not measured
                false,
                "AUTOPILOT_VERSION not answered within 3s");

        String json = JSON.writeValueAsString(VehicleProfileResponse.from(profile));

        assertTrue(json.contains("\"linkKey\":\"udp://0.0.0.0:14550#7\""));
        assertTrue(json.contains("\"sysid\":7"));
        assertTrue(json.contains("\"firmware\":\"ardupilot\""));
        assertTrue(json.contains("\"firmwareVersion\":null"), "unanswered fields must serialize as literal null: " + json);
        assertTrue(json.contains("\"vehicleKind\":null"));
        assertTrue(json.contains("\"capabilityBitmask\":null"));
        assertTrue(json.contains("\"messageId\":33"));
        assertTrue(json.contains("\"name\":\"GLOBAL_POSITION_INT\""));
        assertTrue(json.contains("\"hz\":0.9"));
        assertTrue(json.contains("\"count\":9"));
        assertTrue(json.contains("\"name\":\"SR2_EXTRA2\""));
        assertTrue(json.contains("\"value\":0.0"));
        assertTrue(json.contains("\"type\":\"REAL32\""));
        assertTrue(json.contains("\"linkBytesPerSecond\":null"));
        assertTrue(json.contains("\"complete\":false"));
        assertTrue(json.contains("\"incompleteReason\":\"AUTOPILOT_VERSION not answered within 3s\""));
    }

    @Test
    void readinessReportResponseRenamesFeatureKeyToFeatureAndKeepsRemedyNullWhenAbsent() {
        AssetId assetId = AssetId.random();
        ReadinessReport report = new ReadinessReport(assetId, ReadinessVerdict.NO_GO,
                Instant.parse("2026-08-17T10:04:14Z"), Instant.parse("2026-08-17T10:04:11Z"),
                List.of(new FeatureReadiness("ground-speed", "Ground speed", FeatureStatus.DEGRADED,
                                "VFR_HUD is not arriving (SR2_EXTRA2 = 0)", RemedyKind.MESSAGE_INTERVAL),
                        new FeatureReadiness("fleet-identity", "Fleet identity", FeatureStatus.READY,
                                "SYSID_THISMAV is set", null)),
                List.of("battery"));

        String json = JSON.writeValueAsString(ReadinessReportResponse.from(report));

        assertTrue(json.contains("\"assetId\":\"" + assetId.value() + "\""));
        assertTrue(json.contains("\"verdict\":\"NO_GO\""));
        assertTrue(json.contains("\"feature\":\"ground-speed\""), "wire key must be 'feature', not 'featureKey': " + json);
        assertTrue(json.contains("\"status\":\"DEGRADED\""));
        assertTrue(json.contains("\"remedy\":\"MESSAGE_INTERVAL\""));
        assertTrue(json.contains("\"feature\":\"fleet-identity\""));
        assertTrue(json.contains("\"remedy\":null"), "a row with no remedy must serialize remedy as literal null: " + json);
        assertTrue(json.contains("\"blockers\":[\"battery\"]"));
    }

    @Test
    void readinessReportResponseSerializesNullProfileObservedAtWhenNeverProbed() {
        AssetId assetId = AssetId.random();
        ReadinessReport report = new ReadinessReport(assetId, ReadinessVerdict.UNKNOWN,
                Instant.parse("2026-08-17T10:04:14Z"), null,
                List.of(new FeatureReadiness("map-position", "Map position", FeatureStatus.UNKNOWN,
                        "never probed", null)),
                List.of());

        String json = JSON.writeValueAsString(ReadinessReportResponse.from(report));

        assertTrue(json.contains("\"profileObservedAt\":null"));
    }

    @Test
    void remediationRequestDeserializesFromTheFrozenShape() {
        RemediationRequest request = JSON.readValue(
                "{\"features\":[\"ground-speed\",\"link-quality\"],\"actions\":[\"MESSAGE_INTERVAL\"]}",
                RemediationRequest.class);

        assertEquals(List.of("ground-speed", "link-quality"), request.features());
        assertEquals(List.of("MESSAGE_INTERVAL"), request.actions());
    }

    @Test
    void remediationResultResponseMatchesTheFrozenShapeIncludingExplicitNulls() {
        RemediationActionResponse action = new RemediationActionResponse("MESSAGE_INTERVAL", 74, 200_000L,
                "ACCEPTED", null, null, null);
        RemediationResultResponse result = new RemediationResultResponse(Instant.parse("2026-08-17T10:05:00Z"),
                Instant.parse("2026-08-17T10:05:03Z"), List.of(action), null);

        String json = JSON.writeValueAsString(result);

        assertTrue(json.contains("\"action\":\"MESSAGE_INTERVAL\""));
        assertTrue(json.contains("\"messageId\":74"));
        assertTrue(json.contains("\"intervalMicros\":200000"));
        assertTrue(json.contains("\"outcome\":\"ACCEPTED\""));
        assertTrue(json.contains("\"previousValue\":null"));
        assertTrue(json.contains("\"newValue\":null"));
        assertTrue(json.contains("\"detail\":null"));
        assertTrue(json.contains("\"reprobe\":null"), "verifiedAt/reprobe stay null when the caller supplied them null: " + json);
    }

    @Test
    void readinessRowResponseMatchesTheFleetBoardShape() {
        Asset asset = new Asset(AssetId.random(), "Hexa-7", new CategoryId("multirotor"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(DeviceId.random()), Map.of(),
                LifecycleState.ACTIVE);
        Map<String, String> features = new LinkedHashMap<>();
        features.put("map-position", "READY");
        features.put("battery", "READY");
        ReadinessRowResponse row = new ReadinessRowResponse(asset.id().value().toString(), asset.displayName(),
                "GO", features);

        String json = JSON.writeValueAsString(row);

        assertTrue(json.contains("\"displayName\":\"Hexa-7\""));
        assertTrue(json.contains("\"verdict\":\"GO\""));
        assertTrue(json.contains("\"map-position\":\"READY\""));
        assertTrue(json.contains("\"battery\":\"READY\""));
    }

    @Test
    void probeCandidateRequestDeserializesFromTheFrozenShapeAndDerivesTheLinkKey() {
        ProbeCandidateRequest request = JSON.readValue(
                "{\"protocol\":\"mavlink\",\"uri\":\"udp://0.0.0.0:14550\",\"options\":{\"sysid\":\"7\"}}",
                ProbeCandidateRequest.class);

        assertEquals("mavlink", request.requireProtocol());
        assertEquals("udp://0.0.0.0:14550#7", request.toLinkKey());
    }

    @Test
    void discoveredDeviceResponseCarriesSuggestedOptionsAlongsideTheLegacyDetailsMap() {
        Map<String, String> details = Map.of("sysid", "7");
        String json = JSON.writeValueAsString(
                new DiscoveredDeviceResponse("mavlink-scan", "Aircraft 7", "192.168.1.7", null, null, null, details,
                        Map.of("sysid", "7")));

        assertTrue(json.contains("\"suggestedOptions\":{\"sysid\":\"7\"}"));
        assertTrue(json.contains("\"details\":{\"sysid\":\"7\"}"), "details stays for one release per D16: " + json);
    }

    @Test
    void messageObservationAndParameterReadingResponsesMapEveryField() {
        MessageObservationResponse message = MessageObservationResponse.from(
                new MessageObservation(65, "RC_CHANNELS", 2.0, 20));
        ParameterReadingResponse parameter = ParameterReadingResponse.from(
                new ParameterReading("SYSID_THISMAV", 7.0, "UINT8"));
        FeatureReadinessResponse feature = FeatureReadinessResponse.from(
                new FeatureReadiness("link-quality", "Link quality", FeatureStatus.READY, "RC_CHANNELS is arriving", null));

        assertEquals(65, message.messageId());
        assertEquals("RC_CHANNELS", message.name());
        assertEquals("SYSID_THISMAV", parameter.name());
        assertEquals("link-quality", feature.feature());
        assertEquals("READY", feature.status());
    }

    // ---- FlightPassportResponse / ParameterDriftResponse (O13 -- docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) ----

    private static VehicleProfile minimalProfile(String linkKey, Instant observedAt) {
        return new VehicleProfile(linkKey, observedAt, 7, "ardupilot", "4.5.7", "quadcopter", 12345L,
                List.of("MAVLINK2"), List.of(), List.of(), null, true, null);
    }

    @Test
    void flightPassportResponseOmitsAnUncapturedSnapshotRatherThanSerializingNull() {
        UsageId usageId = UsageId.random();
        AssetId assetId = AssetId.random();
        VehicleProfile preflight = minimalProfile("udp://0.0.0.0:14550#7", Instant.parse("2026-08-19T07:00:00Z"));
        FlightPassport passport = new FlightPassport(usageId, assetId, preflight, null);

        String json = JSON.writeValueAsString(FlightPassportResponse.from(passport));

        assertTrue(json.contains("\"usageId\":\"" + usageId.value() + "\""));
        assertTrue(json.contains("\"assetId\":\"" + assetId.value() + "\""));
        assertTrue(json.contains("\"preflight\":{"));
        assertFalse(json.contains("\"postflight\""), "an uncaptured snapshot must be absent, not null: " + json);
    }

    @Test
    void flightPassportResponseIncludesBothSnapshotsOnceBothAreCaptured() {
        UsageId usageId = UsageId.random();
        AssetId assetId = AssetId.random();
        VehicleProfile preflight = minimalProfile("udp://0.0.0.0:14550#7", Instant.parse("2026-08-18T09:10:00Z"));
        VehicleProfile postflight = minimalProfile("udp://0.0.0.0:14550#7", Instant.parse("2026-08-18T09:40:00Z"));
        FlightPassport passport = new FlightPassport(usageId, assetId, preflight, postflight);

        String json = JSON.writeValueAsString(FlightPassportResponse.from(passport));

        assertTrue(json.contains("\"preflight\":{"));
        assertTrue(json.contains("\"postflight\":{"));
    }

    @Test
    void flightPassportResponseOmitsBothSnapshotsWhenNeitherWasEverCaptured() {
        UsageId usageId = UsageId.random();
        AssetId assetId = AssetId.random();
        FlightPassport passport = new FlightPassport(usageId, assetId, null, null);

        String json = JSON.writeValueAsString(FlightPassportResponse.from(passport));

        assertFalse(json.contains("\"preflight\""));
        assertFalse(json.contains("\"postflight\""));
    }

    @Test
    void parameterDriftResponseMatchesTheFrozenShape() {
        ParameterDrift drift = new ParameterDrift("FENCE_ALT_MAX", 100.0, 120.0,
                Instant.parse("2026-08-18T09:10:00Z"), Instant.parse("2026-08-19T07:02:00Z"));

        String json = JSON.writeValueAsString(ParameterDriftResponse.from(List.of(drift)));

        assertTrue(json.contains("\"parameterName\":\"FENCE_ALT_MAX\""));
        assertTrue(json.contains("\"previousValue\":100.0"));
        assertTrue(json.contains("\"currentValue\":120.0"));
        assertTrue(json.contains("\"previousObservedAt\":\"2026-08-18T09:10:00Z\""));
        assertTrue(json.contains("\"currentObservedAt\":\"2026-08-19T07:02:00Z\""));
    }

    @Test
    void parameterDriftResponseSerializesAnEmptyListAsAnEmptyArrayNeverAnError() {
        String json = JSON.writeValueAsString(ParameterDriftResponse.from(List.of()));

        assertEquals("{\"drift\":[]}", json, "an empty drift list is a correct 200, not withheld/omitted data");
    }
}
