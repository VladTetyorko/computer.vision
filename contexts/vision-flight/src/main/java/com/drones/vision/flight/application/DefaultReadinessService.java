package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.FeatureReadiness;
import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.flight.domain.model.ReadinessVerdict;
import com.drones.vision.flight.domain.model.RemedyKind;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one implementation of {@link ReadinessService}.
 *
 * <h2>The verdict rule -- "absence of evidence is not evidence of readiness"</h2>
 * {@link ReadinessVerdict#UNKNOWN} whenever the asset has never been probed, or its most recent
 * {@link VehicleProfile} is itself incomplete ({@link VehicleProfile#complete()} {@code == false}):
 * a probe that only answered half its questions cannot honestly certify the other half, so nothing
 * here is ever promoted to {@link ReadinessVerdict#GO} on a partial answer. Otherwise, {@link
 * ReadinessVerdict#NO_GO} iff at least one feature evaluates {@link FeatureStatus#MISSING}
 * (collected into {@link ReadinessReport#blockers()}); {@link FeatureStatus#DEGRADED} and a
 * per-feature {@link FeatureStatus#UNKNOWN} (no requirement row for this firmware) are informational
 * and do not by themselves force {@link ReadinessVerdict#NO_GO} -- matching the frozen wire example
 * (section 8.1), whose {@code NO_GO} verdict is driven by a {@code battery} blocker while the only
 * shown feature row is merely {@code DEGRADED}.
 *
 * <h2>Scope</h2>
 * See this class's own module Status entry and the interface javadoc: only the configuration-derived
 * half (profile vs. requirement table) is evaluated here.
 */
public final class DefaultReadinessService implements ReadinessService {

    private final AssetService assetService;
    private final VehicleProfileRepositoryPort profileRepository;
    private final FeatureRequirementRepositoryPort requirementRepository;
    private final Supplier<Instant> clock;

    public DefaultReadinessService(AssetService assetService, VehicleProfileRepositoryPort profileRepository,
                                    FeatureRequirementRepositoryPort requirementRepository) {
        this(assetService, profileRepository, requirementRepository, Instant::now);
    }

    /** Test seam: an injected clock, never {@code Instant.now()} on a test path. */
    DefaultReadinessService(AssetService assetService, VehicleProfileRepositoryPort profileRepository,
                             FeatureRequirementRepositoryPort requirementRepository, Supplier<Instant> clock) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.requirementRepository =
                Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ReadinessReport evaluate(AssetId assetId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        AssetDetails details = assetService.details(scope, assetId); // 404 unknown/out-of-scope
        VehicleProfile profile = latestProfileOf(details).orElse(null);

        List<FeatureRequirement> requirements = profile == null || profile.firmware() == null
                ? List.of()
                : requirementRepository.findByFirmware(profile.firmware());

        List<FeatureReadiness> features = new ArrayList<>();
        for (String featureKey : FeatureRequirement.FEATURE_KEYS) {
            features.add(evaluateFeature(featureKey, profile, requirements));
        }
        features.sort((a, b) -> a.featureKey().compareTo(b.featureKey()));

        boolean unresolvable = profile == null || !profile.complete();
        List<String> blockers = features.stream()
                .filter(f -> f.status() == FeatureStatus.MISSING)
                .map(FeatureReadiness::featureKey)
                .toList();
        ReadinessVerdict verdict = unresolvable
                ? ReadinessVerdict.UNKNOWN
                : blockers.isEmpty() ? ReadinessVerdict.GO : ReadinessVerdict.NO_GO;

        return new ReadinessReport(assetId, verdict, clock.get(),
                profile == null ? null : profile.observedAt(), features,
                verdict == ReadinessVerdict.NO_GO ? blockers : List.of());
    }

    private Optional<VehicleProfile> latestProfileOf(AssetDetails details) {
        for (Device device : details.devices()) {
            Optional<VehicleProfile> found = profileRepository.findLatest(device.id());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private FeatureReadiness evaluateFeature(String featureKey, VehicleProfile profile,
                                              List<FeatureRequirement> requirements) {
        String label = defaultLabel(featureKey);
        if (profile == null) {
            return new FeatureReadiness(featureKey, label, FeatureStatus.UNKNOWN, "Never probed.", null);
        }
        if (!profile.complete()) {
            return new FeatureReadiness(featureKey, label, FeatureStatus.UNKNOWN,
                    "The most recent probe was incomplete: " + profile.incompleteReason(), null);
        }
        Optional<FeatureRequirement> requirement =
                requirements.stream().filter(r -> r.featureKey().equals(featureKey)).findFirst();
        if (requirement.isEmpty()) {
            String firmware = profile.firmware();
            String detail = firmware == null
                    ? "This vehicle never answered which firmware it runs."
                    : "This platform has no requirement known for firmware '" + firmware + "'.";
            return new FeatureReadiness(featureKey, label, FeatureStatus.UNKNOWN, detail, null);
        }
        return evaluateAgainstRequirement(requirement.get(), label, profile);
    }

    private FeatureReadiness evaluateAgainstRequirement(FeatureRequirement req, String label, VehicleProfile profile) {
        FeatureStatus messageStatus = FeatureStatus.READY;
        String messageDetail = null;
        if (req.requiredMessageId() != null) {
            Optional<MessageObservation> observed = profile.messages().stream()
                    .filter(m -> m.messageId() == req.requiredMessageId())
                    .findFirst();
            if (observed.isEmpty()) {
                messageStatus = FeatureStatus.MISSING;
                messageDetail = req.requiredMessageName() + " is not arriving.";
            } else if (observed.get().hz() < req.minimumHz()) {
                messageStatus = FeatureStatus.DEGRADED;
                messageDetail = req.requiredMessageName() + " is arriving at " + observed.get().hz()
                        + " Hz, below the required " + req.minimumHz() + " Hz.";
            }
        }

        FeatureStatus paramStatus = FeatureStatus.READY;
        String paramDetail = null;
        if (req.requiredParameterName() != null) {
            boolean present = profile.parameters().stream()
                    .anyMatch(p -> p.name().equals(req.requiredParameterName()));
            if (!present) {
                paramStatus = FeatureStatus.MISSING;
                paramDetail = "Parameter " + req.requiredParameterName() + " was not read from this vehicle.";
            }
        }

        FeatureStatus status = worseOf(messageStatus, paramStatus);
        String detail = status == FeatureStatus.READY
                ? "Ready."
                : messageStatus != FeatureStatus.READY ? messageDetail : paramDetail;
        RemedyKind remedy = messageStatus != FeatureStatus.READY ? RemedyKind.MESSAGE_INTERVAL : null;
        return new FeatureReadiness(req.featureKey(), label, status, detail, remedy);
    }

    /** {@code MISSING} is worse than {@code DEGRADED} is worse than {@code READY}. */
    private static FeatureStatus worseOf(FeatureStatus a, FeatureStatus b) {
        if (a == FeatureStatus.MISSING || b == FeatureStatus.MISSING) {
            return FeatureStatus.MISSING;
        }
        if (a == FeatureStatus.DEGRADED || b == FeatureStatus.DEGRADED) {
            return FeatureStatus.DEGRADED;
        }
        return FeatureStatus.READY;
    }

    private static String defaultLabel(String featureKey) {
        String[] words = featureKey.split("-");
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                label.append(' ');
            }
            String word = words[i];
            label.append(i == 0 ? word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1) : word);
        }
        return label.toString();
    }
}
