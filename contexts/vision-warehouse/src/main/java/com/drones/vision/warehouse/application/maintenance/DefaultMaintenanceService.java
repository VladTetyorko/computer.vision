package com.drones.vision.warehouse.application.maintenance;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The one implementation of {@link MaintenanceService} and {@link MaintenanceQuery}.
 *
 * <p>{@link #fleetWide} joins {@link MaintenanceRecord} against {@link Asset} directly through the
 * already-injected {@link AssetRepositoryPort} — an in-context join, not a cross-context one (unlike
 * firmware, which stays out of this class entirely; see {@code
 * com.drones.vision.api.support.AssetRowFacts}).
 */
public final class DefaultMaintenanceService implements MaintenanceService, MaintenanceQuery {

    private final MaintenanceRepositoryPort maintenanceRepository;
    private final AssetRepositoryPort assetRepository;
    private final AuditTrailPort auditTrail;

    public DefaultMaintenanceService(MaintenanceRepositoryPort maintenanceRepository,
                                      AssetRepositoryPort assetRepository, AuditTrailPort auditTrail) {
        this.maintenanceRepository =
                Objects.requireNonNull(maintenanceRepository, "maintenanceRepository must not be null");
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public MaintenanceRecord open(AssetId assetId, MaintenanceKind kind, String summary, UserId actor,
                                   VisibilityScope scope) {
        Objects.requireNonNull(kind, "kind must not be null");
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = requireManageable(assetId, scope);

        MaintenanceRecord saved = maintenanceRepository.save(new MaintenanceRecord(MaintenanceId.random(), assetId,
                kind, Instant.now(), null, actor, summary, null));
        audit(actor, assetId, "Opened a " + kind + " maintenance record for asset " + asset.displayName() + ": "
                + summary);
        return saved;
    }

    @Override
    public MaintenanceRecord close(MaintenanceId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        MaintenanceRecord record = maintenanceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown maintenance record: " + id.value()));
        Asset asset = requireManageable(record.assetId(), scope);

        MaintenanceRecord closed = maintenanceRepository.save(record.close(Instant.now()));
        audit(actor, record.assetId(),
                "Closed a " + record.kind() + " maintenance record for asset " + asset.displayName());
        return closed;
    }

    @Override
    public List<MaintenanceRecord> listForAsset(AssetId assetId, VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + assetId.value()));
        if (!scope.includes(assetId, asset.ownership())) {
            // Out of scope: report exactly as "unknown" so existence is not revealed (a 404, not a
            // 403) -- same info-hiding rule as AssetService#details(VisibilityScope, AssetId).
            throw new NoSuchElementException("Unknown asset: " + assetId.value());
        }
        return maintenanceRepository.findByAsset(assetId);
    }

    @Override
    public List<MaintenanceRecordSummary> fleetWide(MaintenanceListState state, int limit, VisibilityScope scope) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<MaintenanceRecord> records = switch (state) {
            case OPEN -> maintenanceRepository.findOpen();
            case CLOSED -> maintenanceRepository.findRecentlyClosed(limit);
            case ALL -> Stream.concat(maintenanceRepository.findOpen().stream(),
                    maintenanceRepository.findRecentlyClosed(limit).stream()).toList();
        };

        List<MaintenanceRecordSummary> summaries = new ArrayList<>();
        for (MaintenanceRecord record : records) {
            assetRepository.findById(record.assetId())
                    .filter(asset -> !asset.isDeleted() && scope.includes(asset.id(), asset.ownership()))
                    .ifPresent(asset -> summaries.add(
                            new MaintenanceRecordSummary(record, asset.displayName(), asset.category())));
        }
        return summaries;
    }

    @Override
    public List<MaintenanceRecord> openBlockers(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        return maintenanceRepository.findOpenByAsset(assetId).stream()
                .filter(record -> record.kind().blocksFlight())
                .toList();
    }

    private Asset requireManageable(AssetId assetId, VisibilityScope scope) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + assetId.value()));
        if (!scope.canManage(asset.ownership())) {
            throw new AccessDeniedException("Asset " + assetId.value() + " is outside your management scope");
        }
        return asset;
    }

    private void audit(UserId actor, AssetId assetId, String summary) {
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), summary));
    }
}
