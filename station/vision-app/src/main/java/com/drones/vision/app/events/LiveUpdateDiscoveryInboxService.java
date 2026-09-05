package com.drones.vision.app.events;

import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Authority;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.RegisterFromCandidateCommand;
import com.drones.vision.warehouse.application.discovery.ReportOutcome;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;

import java.util.List;
import java.util.Objects;

/**
 * {@link DiscoveryInboxService} decorator that additionally announces every candidate change as a
 * {@code discovery} live-update delta (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4).
 *
 * <p><strong>Depends on the concrete {@link LiveUpdateRegistry}, not a per-context {@code
 * *LiveUpdatePort} interface</strong> — a deliberate departure from {@link LiveUpdateEventPublisher}/
 * {@link LiveUpdateAuditTrail}'s own precedent (each of which takes a narrow port owned by the
 * context it decorates). Wave C (this file's own delivery wave) is scoped to {@code vision-api}/
 * {@code vision-app}/{@code storage/persistence} only; {@code vision-warehouse} is out of file
 * scope for this wave (its {@code DiscoveryInboxService}/{@code DefaultDiscoveryInboxService} were
 * already delivered by an earlier wave). Adding a new {@code DiscoveryLiveUpdatePort} to that
 * module's {@code domain.port} package — the "right" long-term shape, matching every other
 * decorator here — would require touching a module this wave does not own. This class instead
 * depends on {@link LiveUpdateRegistry} directly, exactly as {@code vision-app}'s own {@code
 * ApplicationServiceWiring} already imports it concretely for its {@code *LiveUpdatePort} selector
 * beans; {@code vision-app} depending on a concrete {@code vision-api} type is permitted by the
 * dependency rule (adapters &larr; app).
 *
 * <p>Only wired ({@code DiscoveryInboxWiringConfiguration#discoveryInboxService}) when both {@code
 * vision.live.enabled} and {@code vision.discovery.live.enabled} are {@code true}; with either
 * disabled, the plain delegate is used directly and this class is never constructed.
 */
public final class LiveUpdateDiscoveryInboxService implements DiscoveryInboxService {

    private final DiscoveryInboxService delegate;
    private final LiveUpdateRegistry liveUpdateRegistry;

    public LiveUpdateDiscoveryInboxService(DiscoveryInboxService delegate, LiveUpdateRegistry liveUpdateRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.liveUpdateRegistry = Objects.requireNonNull(liveUpdateRegistry, "liveUpdateRegistry must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Announces {@code "REPORTED"} only when {@link ReportOutcome#changed()} is {@code true} —
     * this topic is delta-only by design, never once per sweep regardless of content.
     */
    @Override
    public ReportOutcome report(DiscoveredDevice discovered) {
        ReportOutcome outcome = delegate.report(discovered);
        if (outcome.changed()) {
            liveUpdateRegistry.publishDiscoveryEvent("REPORTED", outcome.candidate());
        }
        return outcome;
    }

    @Override
    public List<DiscoveryCandidate> candidates() {
        return delegate.candidates();
    }

    @Override
    public DiscoveryCandidate dismiss(DiscoveryCandidateId id, UserId actor) {
        DiscoveryCandidate dismissed = delegate.dismiss(id, actor);
        liveUpdateRegistry.publishDiscoveryEvent("DISMISSED", dismissed);
        return dismissed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link #register} itself returns the created {@link Asset}, not the now-{@code REGISTERED}
     * candidate — this re-reads {@link DiscoveryInboxService#candidates()} for the one matching
     * {@code id} to announce its final state, mirroring how a caller would otherwise have to
     * refresh the inbox list after a register anyway.
     */
    @Override
    public Asset register(DiscoveryCandidateId id, RegisterFromCandidateCommand command, Authority scope,
                           UserId actor) {
        Asset asset = delegate.register(id, command, scope, actor);
        delegate.candidates().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .ifPresent(candidate -> liveUpdateRegistry.publishDiscoveryEvent("REGISTERED", candidate));
        return asset;
    }

    @Override
    public DiscoveryCandidate attach(DiscoveryCandidateId id, AssetId assetId, Authority scope, UserId actor) {
        DiscoveryCandidate attached = delegate.attach(id, assetId, scope, actor);
        liveUpdateRegistry.publishDiscoveryEvent("REGISTERED", attached);
        return attached;
    }

    @Override
    public DiscoveryCandidate restore(DiscoveryCandidateId id, UserId actor) {
        DiscoveryCandidate restored = delegate.restore(id, actor);
        liveUpdateRegistry.publishDiscoveryEvent("RESTORED", restored);
        return restored;
    }
}
