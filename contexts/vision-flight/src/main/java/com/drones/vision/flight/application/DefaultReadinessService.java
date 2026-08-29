package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.FeatureReadiness;
import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.model.FeatureStatus;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterAliases;
import com.drones.vision.flight.domain.model.ParameterReading;
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
import com.drones.vision.warehouse.application.maintenance.MaintenanceQuery;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * half (profile vs. requirement table) is evaluated here -- plus, since WAREHOUSE-UX wave W5, a
 * third, independent source of {@link ReadinessVerdict#NO_GO}: an asset's open, flight-blocking
 * warehouse {@link MaintenanceRecord}s (see {@link #maintenanceBlockers}). The telemetry-derived
 * half (video/telemetry/GPS/battery/armable) remains an intentional gap.
 *
 * <h2>Maintenance blockers (WAREHOUSE-UX-CONTEXT.md D6/OQ1)</h2>
 * A manager grounding an asset ({@code AssetCustodyService#ground}, or any open {@code GROUNDING}/
 * {@code INSPECTION_DUE} record) is a harder fact than the configuration-derived verdict above --
 * it forces {@link ReadinessVerdict#NO_GO} even when the profile has never been probed ({@code
 * UNKNOWN} would otherwise apply): "grounded" must never read as merely "unknown". Each open
 * blocking record contributes one {@link ReadinessReport#blockers()} entry, prefixed {@link
 * #MAINTENANCE_BLOCKER_PREFIX} and carrying the record's own {@code kind} and {@code summary} --
 * deliberately not a {@link FeatureReadiness} row, since {@code featureKey} is validated against the
 * frozen {@link FeatureRequirement#FEATURE_KEYS} eleven-key set and a maintenance record is not one
 * of those keys. {@code blockers} carries no such constraint and the wire ({@code
 * ReadinessReportResponse}) already renders it verbatim, so this rides the existing shape with zero
 * new wire surface. {@link DefaultManualControlService#engage} checks for this exact prefix to
 * refuse manual control on a grounded asset.
 *
 * <h2>Value/bit-aware parameter checks (FLEET-RADIO-PLAN.md R6)</h2>
 * A {@link FeatureRequirement} row's {@code requiredParameterName} was, before this wave, a
 * presence-only check ("was this parameter ever read"). {@link FeatureRequirement#requiredParameterValue()}
 * (must equal exactly) and {@link FeatureRequirement#forbiddenParameterBits()} (must have none of
 * these bits set) let a row assert what the parameter's value actually has to be -- needed because
 * two ArduPilot settings silently discard every MAVLink RC override with no error and no wire
 * response: the vehicle's GCS-sysid parameter not matching the sysid this platform transmits as
 * (255), and {@code RC_OPTIONS} bit 1 ({@code IGNORE_OVERRIDES}) being set. Both are seeded as two
 * independent rows under the existing {@code rc-relay} key (not two new frozen keys — see {@link
 * #combine} and this wave's own report/plan entry for why) and folded into one {@link
 * FeatureReadiness} by {@link #combine}.
 */
public final class DefaultReadinessService implements ReadinessService {

    /**
     * The {@link ReadinessReport#blockers()} entry prefix a maintenance blocker uses:
     * {@code "<prefix><kind>:<summary>"}, e.g. {@code
     * "MAINTENANCE_GROUNDED:GROUNDING:Propeller crack found on preflight"}. See this class's own
     * "Maintenance blockers" javadoc section for why this rides the existing {@code List<String>}
     * shape rather than becoming a new {@link FeatureReadiness} row.
     */
    public static final String MAINTENANCE_BLOCKER_PREFIX = "MAINTENANCE_GROUNDED:";

    private final AssetService assetService;
    private final VehicleProfileRepositoryPort profileRepository;
    private final FeatureRequirementRepositoryPort requirementRepository;
    private final MaintenanceQuery maintenanceQuery;
    private final Supplier<Instant> clock;

    public DefaultReadinessService(AssetService assetService, VehicleProfileRepositoryPort profileRepository,
                                    FeatureRequirementRepositoryPort requirementRepository,
                                    MaintenanceQuery maintenanceQuery) {
        this(assetService, profileRepository, requirementRepository, maintenanceQuery, Instant::now);
    }

    /** Test seam: an injected clock, never {@code Instant.now()} on a test path. */
    DefaultReadinessService(AssetService assetService, VehicleProfileRepositoryPort profileRepository,
                             FeatureRequirementRepositoryPort requirementRepository, MaintenanceQuery maintenanceQuery,
                             Supplier<Instant> clock) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.requirementRepository =
                Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.maintenanceQuery = Objects.requireNonNull(maintenanceQuery, "maintenanceQuery must not be null");
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
        List<String> configBlockers = features.stream()
                .filter(f -> f.status() == FeatureStatus.MISSING)
                .map(FeatureReadiness::featureKey)
                .toList();
        List<String> maintenanceBlockers = maintenanceBlockers(assetId);

        ReadinessVerdict verdict = !maintenanceBlockers.isEmpty()
                // A maintenance blocker is a harder fact than "never probed" -- it must win over
                // UNKNOWN too, not only over a would-be GO. See this class's own "Maintenance
                // blockers" javadoc section.
                ? ReadinessVerdict.NO_GO
                : unresolvable
                        ? ReadinessVerdict.UNKNOWN
                        : configBlockers.isEmpty() ? ReadinessVerdict.GO : ReadinessVerdict.NO_GO;
        List<String> blockers = verdict == ReadinessVerdict.NO_GO
                ? Stream.concat(configBlockers.stream(), maintenanceBlockers.stream()).toList()
                : List.of();

        return new ReadinessReport(assetId, verdict, clock.get(),
                profile == null ? null : profile.observedAt(), features, blockers);
    }

    /**
     * WAREHOUSE-UX-CONTEXT.md D6/OQ1: this asset's currently-open, flight-blocking warehouse
     * maintenance records, rendered as {@link ReadinessReport#blockers()} entries. Filters
     * defensively on {@link MaintenanceRecord#isOpen()}/{@link MaintenanceKind#blocksFlight()}
     * rather than trusting {@link MaintenanceQuery#openBlockers}'s own "open, blocking only"
     * contract alone -- the same "defense in depth, not the mechanism" posture this module already
     * applies elsewhere to a cross-boundary contract (see {@code ControlProfile.forKind(UNKNOWN)}'s
     * own javadoc for the precedent).
     *
     * @param assetId the asset to check
     * @return one {@link #MAINTENANCE_BLOCKER_PREFIX}-prefixed string per open blocking record;
     *         empty when the asset carries none
     */
    private List<String> maintenanceBlockers(AssetId assetId) {
        return maintenanceQuery.openBlockers(assetId).stream()
                .filter(MaintenanceRecord::isOpen)
                .filter(record -> record.kind().blocksFlight())
                .map(record -> MAINTENANCE_BLOCKER_PREFIX + record.kind().name() + ":" + record.summary())
                .toList();
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
                                              List<FeatureRequirement> allRequirements) {
        String label = defaultLabel(featureKey);
        if (profile == null) {
            return new FeatureReadiness(featureKey, label, FeatureStatus.UNKNOWN, "Never probed.", null);
        }
        if (!profile.complete()) {
            return new FeatureReadiness(featureKey, label, FeatureStatus.UNKNOWN,
                    "The most recent probe was incomplete: " + profile.incompleteReason(), null);
        }
        List<FeatureRequirement> requirements =
                allRequirements.stream().filter(r -> r.featureKey().equals(featureKey)).toList();
        if (requirements.isEmpty()) {
            String firmware = profile.firmware();
            String detail = firmware == null
                    ? "This vehicle never answered which firmware it runs."
                    : "This platform has no requirement known for firmware '" + firmware + "'.";
            return new FeatureReadiness(featureKey, label, FeatureStatus.UNKNOWN, detail, null);
        }
        List<FeatureReadiness> evaluated = requirements.stream()
                .map(requirement -> evaluateAgainstRequirement(requirement, label, profile))
                .toList();
        return combine(featureKey, label, evaluated);
    }

    /**
     * Folds every {@link FeatureRequirement} row seeded for one feature key into the single {@link
     * FeatureReadiness} the wire contract allows per key -- a fleet-board row is a {@code
     * {featureKey: status}} map ({@code ReadinessRowResponse}, vision-api), which structurally cannot
     * hold two statuses under one key ({@code V18__feature_requirements.sql}'s own note on this). The
     * common case is exactly one row, and this is then a pass-through. {@code rc-relay} (FLEET-RADIO-PLAN.md
     * R6) is the first feature ever seeded with more than one row -- GCS sysid and {@code RC_OPTIONS}
     * are two independent facts, either one alone enough to silently discard every stick input, so
     * both are seeded as separate rows and combined here rather than repurposing {@link
     * FeatureRequirement}'s single {@code requiredParameterName} slot to check two parameters at
     * once. Worst status wins ({@link #worseOf}); a non-{@code READY} result's detail is every
     * failing row's own detail, joined (so a vehicle failing both checks at once reads both
     * sentences, not just one); the remedy is the first non-null remedy among the rows at the worst
     * status (both of {@code rc-relay}'s R6 rows resolve to {@link RemedyKind#PARAM_WRITE}, so there
     * is no ambiguity to resolve in practice today).
     */
    private static FeatureReadiness combine(String featureKey, String label, List<FeatureReadiness> evaluated) {
        if (evaluated.size() == 1) {
            return evaluated.get(0);
        }
        FeatureStatus status = FeatureStatus.READY;
        for (FeatureReadiness readiness : evaluated) {
            status = worseOf(status, readiness.status());
        }
        if (status == FeatureStatus.READY) {
            return new FeatureReadiness(featureKey, label, FeatureStatus.READY, "Ready.", null);
        }
        FeatureStatus worst = status;
        String detail = evaluated.stream()
                .filter(r -> r.status() != FeatureStatus.READY)
                .map(FeatureReadiness::detail)
                .collect(Collectors.joining(" "));
        RemedyKind remedy = evaluated.stream()
                .filter(r -> r.status() == worst)
                .map(FeatureReadiness::remedy)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new FeatureReadiness(featureKey, label, status, detail, remedy);
    }

    /**
     * A tiny floating-point tolerance for {@link FeatureRequirement#requiredParameterValue()}
     * comparisons -- MAVLink parameters always wire as float32 regardless of their declared type, so
     * an integer value like {@code 255} round-trips exactly, but this stays defensive against any
     * future non-integer threshold.
     */
    private static final double PARAMETER_VALUE_TOLERANCE = 1e-6;

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
            // Alias-aware: a firmware rename must not read as a missing parameter (ParameterAliases).
            Optional<ParameterReading> observed = profile.parameters().stream()
                    .filter(p -> ParameterAliases.sameParameter(p.name(), req.requiredParameterName()))
                    .findFirst();
            if (observed.isEmpty()) {
                paramStatus = FeatureStatus.MISSING;
                paramDetail = "Parameter " + req.requiredParameterName() + " was not read from this vehicle.";
            } else if (req.requiredParameterValue() != null
                    && Math.abs(observed.get().value() - req.requiredParameterValue()) > PARAMETER_VALUE_TOLERANCE) {
                // FLEET-RADIO-PLAN.md R6 -- e.g. the vehicle's GCS-sysid parameter must equal exactly
                // the sysid this platform transmits as (255), or every MAVLink RC override it sends
                // is silently discarded by the vehicle, with no error and no wire response at all.
                paramStatus = FeatureStatus.MISSING;
                paramDetail = req.requiredParameterName() + " is " + formatValue(observed.get().value())
                        + ", not the required " + formatValue(req.requiredParameterValue()) + ".";
            } else if (req.forbiddenParameterBits() != null
                    && (Math.round(observed.get().value()) & req.forbiddenParameterBits()) != 0) {
                // FLEET-RADIO-PLAN.md R6 -- e.g. ArduPilot's RC_OPTIONS bit 1 (IGNORE_OVERRIDES) SET
                // means the vehicle ignores MAVLink RC overrides, so the requirement is that the bit
                // be CLEAR. Getting this polarity backwards would report NO_GO for every correctly
                // configured vehicle and GO for every misconfigured one.
                paramStatus = FeatureStatus.MISSING;
                paramDetail = req.requiredParameterName() + " forbids bit(s) " + req.forbiddenParameterBits()
                        + ", but its value is " + formatValue(observed.get().value()) + ".";
            }
        }

        FeatureStatus status = worseOf(messageStatus, paramStatus);
        String detail = status == FeatureStatus.READY
                ? "Ready."
                : messageStatus != FeatureStatus.READY ? messageDetail : paramDetail;
        // A param-only failure must recommend PARAM_WRITE, not silently carry no remedy at all --
        // before FLEET-RADIO-PLAN.md R6 this branch always fell through to null (untested; no
        // existing row's MISSING case had ever asserted its remedy).
        RemedyKind remedy = messageStatus != FeatureStatus.READY
                ? RemedyKind.MESSAGE_INTERVAL
                : paramStatus != FeatureStatus.READY ? RemedyKind.PARAM_WRITE : null;
        return new FeatureReadiness(req.featureKey(), label, status, detail, remedy);
    }

    /** Whole numbers render without a trailing {@code .0} -- every value this method sees today
     * (sysid, a bitmask) is conceptually an integer. */
    private static String formatValue(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? Long.toString((long) value)
                : Double.toString(value);
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
