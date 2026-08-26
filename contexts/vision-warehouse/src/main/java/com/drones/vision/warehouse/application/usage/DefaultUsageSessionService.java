package com.drones.vision.warehouse.application.usage;

import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.time.Instant;
import java.util.Objects;

/**
 * {@link UsageSessionService} default implementation: the only place in the codebase that
 * constructs a new {@link AssetUsage} ({@link #open}) or writes one to {@link
 * AssetUsageRepositoryPort} ({@link #open}, {@link #close}, {@link #save}) — see the interface
 * javadoc.
 */
public final class DefaultUsageSessionService implements UsageSessionService {

    private final AssetUsageRepositoryPort usageRepository;

    public DefaultUsageSessionService(AssetUsageRepositoryPort usageRepository) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
    }

    @Override
    public AssetUsage open(AssetId assetId, StreamId streamIdOrNull, Instant startedAt) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        AssetUsage usage = new AssetUsage(UsageId.random(), assetId, startedAt, null, null, null, 0L, streamIdOrNull,
                UsagePhase.PREFLIGHT);
        return usageRepository.save(usage);
    }

    @Override
    public AssetUsage fold(AssetUsage usage, GeoPosition position, UsagePhase phase) {
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        GeoPosition startPosition = usage.startPosition() != null ? usage.startPosition() : position;
        GeoPosition lastPosition = position != null ? position : usage.lastPosition();
        return usage.withPositions(startPosition, lastPosition)
                .withSampleCount(usage.sampleCount() + 1)
                .withPhase(phase);
    }

    @Override
    public AssetUsage updatePhase(AssetUsage usage, UsagePhase phase) {
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        return usage.withPhase(phase);
    }

    @Override
    public AssetUsage close(AssetUsage usage, UsagePhase phase, Instant endedAt) {
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null");
        AssetUsage closedUsage = usage.closed(endedAt).withPhase(phase);
        return usageRepository.save(closedUsage);
    }

    @Override
    public AssetUsage save(AssetUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        return usageRepository.save(usage);
    }
}
