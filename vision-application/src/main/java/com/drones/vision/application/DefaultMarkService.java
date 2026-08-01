package com.drones.vision.application;

import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GeoProjection;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.MarkRepositoryPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The one implementation of {@link MarkService}.
 *
 * <h2>Geolocation</h2>
 * {@link #geolocate} is the one place this class does more than CRUD: it reads {@code assetId}'s
 * freshest sample via {@link UsageTracker#latestTelemetry}, honestly validates it (latitude,
 * longitude and heading present; altitude present and positive), and only then builds the drone's
 * {@link GeoPosition} and calls {@link GeoProjection#project} — {@code GeoProjection} itself accepts
 * no nullable telemetry (docs/TACTICAL-MARKS-PLAN.md's M1 handoff note), so this null-checking is
 * this class's job, not the pure geo-math's.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state is reached through the injected
 * ports/collaborators.
 */
public final class DefaultMarkService implements MarkService {

    private static final String TELEMETRY_INCOMPLETE = "cannot geolocate: telemetry incomplete";

    private final MarkRepositoryPort markRepository;
    private final UsageTracker usageTracker;
    private final LiveUpdatePublisherPort liveUpdatePublisher;

    public DefaultMarkService(MarkRepositoryPort markRepository, UsageTracker usageTracker,
                               LiveUpdatePublisherPort liveUpdatePublisher) {
        this.markRepository = Objects.requireNonNull(markRepository, "markRepository must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.liveUpdatePublisher =
                Objects.requireNonNull(liveUpdatePublisher, "liveUpdatePublisher must not be null");
    }

    @Override
    public List<Mark> list() {
        return markRepository.findAll().stream()
                .filter(mark -> mark.status() == MarkStatus.ACTIVE)
                .sorted(Comparator.comparing(Mark::createdAt).reversed())
                .toList();
    }

    @Override
    public Mark create(MarkSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Mark mark = new Mark(MarkId.random(), spec.position(), spec.kind(), spec.label(), spec.note(),
                ownership, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL);
        Mark saved = markRepository.save(mark);
        liveUpdatePublisher.publishMarkCreated(saved);
        return saved;
    }

    @Override
    public Mark geolocate(GeolocateSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Telemetry telemetry = usageTracker.latestTelemetry(spec.assetId())
                .orElseThrow(() -> new IllegalArgumentException(TELEMETRY_INCOMPLETE));
        if (telemetry.latitude() == null || telemetry.longitude() == null || telemetry.headingDegrees() == null
                || telemetry.altitudeMeters() == null || telemetry.altitudeMeters() <= 0) {
            throw new IllegalArgumentException(TELEMETRY_INCOMPLETE);
        }

        GeoPosition drone = new GeoPosition(telemetry.latitude(), telemetry.longitude(), telemetry.altitudeMeters());
        GeoPosition ground = GeoProjection.project(drone, telemetry.headingDegrees(), telemetry.altitudeMeters(),
                spec.depressionDegrees());

        Mark mark = new Mark(MarkId.random(), ground, spec.kind(), spec.label(), spec.note(),
                ownership, Instant.now(), MarkStatus.ACTIVE, MarkSource.DETECTION);
        Mark saved = markRepository.save(mark);
        liveUpdatePublisher.publishMarkCreated(saved);
        return saved;
    }

    @Override
    public Mark update(MarkId id, MarkPatch patch, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        // Creator-or-manager, uniformly for every field -- including a plain annotation/drag-to-correct
        // with no status change. No group-visibility gate in front of this any more: list() is now
        // deployment-wide, so a PILOT must be able to reach (and manage) their own mark by id.
        Mark mark = require(id);
        requireCreatorOrManager(mark, actor, scope);

        MarkKind kind = patch.kind().orElse(mark.kind());
        String label = patch.label().orElse(mark.label());
        String note = patch.note().orElse(mark.note());
        GeoPosition position = patch.position().orElse(mark.position());
        Mark annotated = mark.withDetails(kind, label, note, position);
        Mark updated = patch.status().isPresent() ? annotated.withStatus(patch.status().get()) : annotated;

        Mark saved = markRepository.save(updated);
        if (saved.status() == MarkStatus.CLEARED) {
            liveUpdatePublisher.publishMarkCleared(saved);
        } else {
            liveUpdatePublisher.publishMarkUpdated(saved);
        }
        return saved;
    }

    @Override
    public void delete(MarkId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Mark mark = require(id);
        requireCreatorOrManager(mark, actor, scope);
        markRepository.deleteById(id);
        liveUpdatePublisher.publishMarkCleared(mark);
    }

    private Mark require(MarkId id) {
        Objects.requireNonNull(id, "id must not be null");
        return markRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown mark: " + id.value()));
    }

    /**
     * The creator-or-manager gate shared by {@link #update} (every field, not only a status
     * transition) and {@link #delete}: {@code actor.equals(mark.createdBy())} (the creator may
     * always manage their own mark) or {@link VisibilityScope#canManageOrg()} (a manager/admin may
     * manage any mark), else {@link AccessDeniedException}. Deliberately not additionally
     * scope-checked against the mark's owning group: any manager, not only one whose subtree
     * contains this mark, may edit/clear/delete it.
     */
    private void requireCreatorOrManager(Mark mark, UserId actor, VisibilityScope scope) {
        if (!mark.ownership().ownerId().equals(actor) && !scope.canManageOrg()) {
            throw new AccessDeniedException(
                    "Mark " + mark.id().value() + " may only be edited or deleted by its creator or a manager");
        }
    }
}
