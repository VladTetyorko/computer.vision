package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.asset.DuplicateDeviceMatch;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.CandidateStatus;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import com.drones.vision.warehouse.domain.port.DiscoveryCandidateRepositoryPort;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The one implementation of {@link DiscoveryInboxService}.
 *
 * <h2>Why {@link #report} is {@code synchronized}</h2>
 * {@link #report} is called from a periodic sweep runner ({@code vision-app}'s wiring, outside this
 * module) and may also be called from an operator-triggered manual scan concurrently with a sweep
 * still in flight. Its upsert is a plain read ({@code
 * DiscoveryCandidateRepositoryPort#findByIdentityKey}) followed by a write ({@code #save}) with no
 * atomicity guarantee across the two — two concurrent reports for the <em>same</em> identity could
 * otherwise both observe "no existing candidate" and each insert its own row, or race each other's
 * {@code lastSeen} update. Rather than pushing an atomic-upsert requirement onto every {@code
 * DiscoveryCandidateRepositoryPort} implementation (a background sweep every tens of seconds, over
 * at most a few dozen candidates, is not a hot path), this class serializes its own mutating methods
 * ({@link #report}, {@link #dismiss}, {@link #register}, {@link #attach}, {@link #restore}) against
 * one lock. Coarse-grained on purpose: correctness over throughput for a call pattern this
 * infrequent.
 */
public final class DefaultDiscoveryInboxService implements DiscoveryInboxService {

    /**
     * The one protocol in this codebase that is telemetry and nothing else. Declared locally rather
     * than shared, exactly as {@code vision-api}'s {@code CapabilityParsing} and {@code
     * DefaultSimulationService} each declare their own copy — this module may not depend on {@code
     * vision-api} or {@code adapter-mavlink}, where an authoritative constant could otherwise live
     * (ArchUnit-enforced).
     */
    private static final String PROTOCOL_MAVLINK = "mavlink";

    private final DiscoveryCandidateRepositoryPort candidateRepository;
    private final AssetService assetService;
    private final DeviceService deviceService;
    private final Supplier<Instant> clock;

    public DefaultDiscoveryInboxService(DiscoveryCandidateRepositoryPort candidateRepository,
                                         AssetService assetService, DeviceService deviceService) {
        this(candidateRepository, assetService, deviceService, Instant::now);
    }

    /**
     * Test seam: same as the 3-argument constructor, with an explicit "now" supplier so {@code
     * firstSeen}/{@code lastSeen} assertions never depend on wall-clock timing. Production always
     * uses the 3-argument constructor's {@link Instant#now()} default.
     */
    DefaultDiscoveryInboxService(DiscoveryCandidateRepositoryPort candidateRepository, AssetService assetService,
                                  DeviceService deviceService, Supplier<Instant> clock) {
        this.candidateRepository = Objects.requireNonNull(candidateRepository, "candidateRepository must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized ReportOutcome report(DiscoveredDevice discovered) {
        Objects.requireNonNull(discovered, "discovered must not be null");
        String identityKey = DiscoveryCandidate.identityKeyFor(discovered);
        Instant now = clock.get();

        Optional<DiscoveryCandidate> existing = candidateRepository.findByIdentityKey(identityKey);
        DiscoveryCandidate base = existing
                .map(candidate -> candidate.reSeen(discovered, now))
                .orElseGet(() -> DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, now));

        Optional<DuplicateDeviceMatch> duplicate = discovered.suggestedStream() == null
                ? Optional.empty()
                : assetService.findDuplicateDevice(discovered.suggestedStream());

        DiscoveryCandidate resolved;
        if (duplicate.isPresent()) {
            resolved = base.registeredTo(duplicate.get().owningAsset());
        } else if (base.status() == CandidateStatus.REGISTERED) {
            // Auto-reopen (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.2 C5): the device or
            // asset this candidate previously matched is gone, so an operator should see it again.
            resolved = base.restore();
        } else {
            resolved = base;
        }

        DiscoveryCandidate saved = candidateRepository.save(resolved);
        boolean changed = existing.isEmpty()
                || existing.get().status() != saved.status()
                || !existing.get().discovered().equals(saved.discovered());
        return new ReportOutcome(saved, changed);
    }

    @Override
    public List<DiscoveryCandidate> candidates() {
        return candidateRepository.findAll();
    }

    @Override
    public synchronized DiscoveryCandidate dismiss(DiscoveryCandidateId id, UserId actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        DiscoveryCandidate candidate = require(id);
        return candidateRepository.save(candidate.dismiss());
    }

    @Override
    public synchronized Asset register(DiscoveryCandidateId id, RegisterFromCandidateCommand command,
                                        Authority scope, UserId actor) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (!scope.mayManageOrg()) {
            throw new AccessDeniedException("not permitted to register a discovery candidate");
        }
        if (!scope.scope().includesGroup(command.ownership().groupId())) {
            throw new AccessDeniedException("cannot register an asset owned by a group outside your scope");
        }

        DiscoveryCandidate candidate = require(id);
        Asset asset = assetService.createFromCandidate(toAssetSpec(candidate.discovered(), command),
                command.ownership(), actor);

        candidateRepository.save(candidate.registeredTo(asset.id()));
        return asset;
    }

    @Override
    public synchronized DiscoveryCandidate attach(DiscoveryCandidateId id, AssetId assetId, Authority scope,
                                                   UserId actor) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (!scope.mayManageOrg()) {
            throw new AccessDeniedException("not permitted to attach a discovery candidate");
        }

        DiscoveryCandidate candidate = require(id);
        if (candidate.status() == CandidateStatus.REGISTERED && candidate.registeredAsset() != null
                && !candidate.registeredAsset().equals(assetId)) {
            throw new DiscoveryCandidateAlreadyRegisteredException(id, candidate.registeredAsset());
        }

        StreamDescriptor suggestedStream = candidate.discovered().suggestedStream();
        if (suggestedStream == null) {
            throw new IllegalStateException(
                    "Discovery candidate " + id.value() + " has no usable stream to attach");
        }

        // 404, never 403, for an unknown-or-out-of-scope target asset (docs/plans/active/
        // SOURCE-ONBOARDING-2-PLAN.md §3.2 C1) — the same scoped-read convention every other 404
        // here follows, so this check never reveals whether an out-of-scope asset exists.
        assetService.details(scope.scope(), assetId);
        requireNoDuplicateStream(suggestedStream);

        DeviceRegistration registration = new DeviceRegistration(candidate.discovered().name(),
                capabilitiesFor(suggestedStream), suggestedStream);
        Device device = deviceService.register(registration, actor);
        assetService.assignDevice(assetId, device.id(), actor);

        return candidateRepository.save(candidate.registeredTo(assetId));
    }

    @Override
    public synchronized DiscoveryCandidate restore(DiscoveryCandidateId id, UserId actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        DiscoveryCandidate candidate = require(id);
        return candidateRepository.save(candidate.restore());
    }

    private DiscoveryCandidate require(DiscoveryCandidateId id) {
        Objects.requireNonNull(id, "id must not be null");
        return candidateRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown discovery candidate: " + id.value()));
    }

    /**
     * The duplicate check {@link #attach} needs before registering a new device, mirroring {@code
     * DefaultAssetService#requireNoDuplicate}'s message shape exactly (docs/plans/active/
     * SOURCE-ONBOARDING-2-PLAN.md §3.2 C1: "message names the owning asset, exactly like
     * createFromCandidate's throw"). Declared here rather than shared because {@link AssetService}
     * exposes only the query form ({@link AssetService#findDuplicateDevice}); the throwing form is
     * private to {@code DefaultAssetService}.
     *
     * @throws IllegalStateException if an active device already carries this (protocol, uri, sysid)
     */
    private void requireNoDuplicateStream(StreamDescriptor candidateStream) {
        assetService.findDuplicateDevice(candidateStream).ifPresent(match -> {
            String owner = match.owningAsset() != null
                    ? " already registered to asset "
                            + assetService.details(match.owningAsset()).summary().asset().displayName()
                    : " already registered to device "
                            + deviceService.find(match.deviceId()).map(Device::name).orElse(match.deviceId().value().toString());
            throw new IllegalStateException(
                    "Candidate " + candidateStream.protocol() + " " + candidateStream.uri() + " is" + owner);
        });
    }

    private static AssetSpec toAssetSpec(DiscoveredDevice discovered, RegisterFromCandidateCommand command) {
        DeviceRegistration registration = new DeviceRegistration(discovered.name(),
                capabilitiesFor(discovered.suggestedStream()), discovered.suggestedStream());
        return new AssetSpec(command.displayName(), command.category(), command.attributes(),
                List.of(registration), List.of(), command.identity(), Custody.NONE);
    }

    /**
     * What a device of this candidate's protocol exposes when nothing more specific is known —
     * mirrors {@code vision-api}'s {@code CapabilityParsing#defaultsFor}: {@code mavlink} is
     * telemetry-only, everything else defaults to video. {@code stream} is {@code null}-safe purely
     * so the failure a {@code null} suggested stream causes surfaces from {@link
     * DeviceRegistration}'s own compact constructor ("stream must not be null"), not from here.
     */
    private static Set<Capability> capabilitiesFor(StreamDescriptor stream) {
        String protocol = stream == null ? null : stream.protocol();
        if (protocol != null && PROTOCOL_MAVLINK.equalsIgnoreCase(protocol.trim())) {
            return Set.of(Capability.TELEMETRY);
        }
        return Set.of(Capability.VIDEO);
    }
}
