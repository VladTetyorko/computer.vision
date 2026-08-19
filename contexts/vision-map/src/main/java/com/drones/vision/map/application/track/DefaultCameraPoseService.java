package com.drones.vision.map.application.track;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.port.CameraPoseRepositoryPort;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one implementation of {@link CameraPoseService}.
 *
 * <p>Deliberately holds no dependency on any warehouse port — {@link CameraPose} is keyed by {@link
 * AssetId} (a kernel type), and per docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D2 that is
 * enough: "structurally the frozen DAG prices a new edge higher than filing 'where a sensor sits on
 * the shared picture' with the picture." Whether {@code assetId} actually names a real, visible asset
 * is the {@code vision-api} edge's job (D10), not this service's.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultCameraPoseService implements CameraPoseService {

    private final CameraPoseRepositoryPort repository;
    private final AuditTrailPort auditTrail;

    public DefaultCameraPoseService(CameraPoseRepositoryPort repository, AuditTrailPort auditTrail) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public List<CameraPose> list() {
        return repository.findAll();
    }

    @Override
    public Optional<CameraPose> find(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        return repository.findByAssetId(assetId);
    }

    @Override
    public CameraPose put(AssetId assetId, CameraPoseInput input, UserId actor) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        boolean existedBefore = repository.findByAssetId(assetId).isPresent();
        CameraPose pose = new CameraPose(assetId, input.position(), input.aglMeters(), input.yawDegrees(),
                input.pitchDegrees(), input.hfovDegrees(), input.targetLayerId(), input.source(),
                input.rmsErrorPixels(), Instant.now(), actor);
        CameraPose saved = repository.save(pose);

        AuditAction action = existedBefore ? AuditAction.UPDATED : AuditAction.CREATED;
        auditTrail.record(AuditEntry.of(actor, action, AuditTargetType.ASSET, assetId.value().toString(),
                (existedBefore ? "Updated" : "Set") + " camera pose for asset " + assetId.value(), Map.of()));
        return saved;
    }

    @Override
    public void delete(AssetId assetId, UserId actor) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        boolean existed = repository.findByAssetId(assetId).isPresent();
        repository.deleteByAssetId(assetId);
        if (existed) {
            auditTrail.record(AuditEntry.of(actor, AuditAction.DELETED, AuditTargetType.ASSET,
                    assetId.value().toString(), "Deleted camera pose for asset " + assetId.value(), Map.of()));
        }
    }
}
