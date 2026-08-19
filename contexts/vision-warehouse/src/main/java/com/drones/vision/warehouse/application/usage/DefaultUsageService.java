package com.drones.vision.warehouse.application.usage;

import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UsageService} default implementation: reads {@link AssetUsageRepositoryPort} for the
 * candidate usages and {@link AssetRepositoryPort} to resolve each one's owning asset — both for
 * display (the asset's {@code displayName}) and for visibility (dropping any usage {@code scope}
 * may not see).
 *
 * <h2>Scope filtering happens after the repository's own {@code limit}</h2>
 * {@link #recent} asks the repository for at most {@code limit} candidates (newest first), then
 * drops the ones {@code scope} may not see. A scoped caller (a manager or pilot, not ADMIN/auth-off)
 * can therefore see fewer than {@code limit} rows even when more of their own flights exist further
 * back in history — a known, accepted limitation for this first cut (mirrors {@code
 * DefaultAssetStatsService}'s own documented "fetch-then-aggregate against a cap" posture), not a
 * real time-bounded/paginated query. Fine for the "browse the last N flights" library this backs;
 * revisit with a scope-aware repository query if that ever stops being true.
 *
 * <h2>An asset that no longer resolves</h2>
 * {@link AssetRepositoryPort#findById} can return {@link Optional#empty()} for a usage whose asset
 * row is genuinely gone (not the common soft-delete case — a soft-deleted asset still has a row,
 * and therefore an {@link Asset#ownership()} to scope-check against; this is the rarer case of a
 * hard-deleted or otherwise missing asset id). With no {@code Asset} to resolve a name or an
 * ownership from, this class takes the safe default: such a usage is included only for an {@link
 * VisibilityScope#isUnbounded()} caller (with {@code assetName=""}), and silently dropped for
 * every other scope — the same "hide what you can't verify" posture {@link VisibilityScope}'s own
 * scoped reads already use elsewhere in this codebase, rather than guessing at visibility for a
 * usage nobody can prove is (or isn't) the caller's own.
 *
 * <h2>{@code byStream} reuses both of the above</h2>
 * {@link #byStream} runs the same {@code toSummary} resolution over a single repository hit, so a
 * usage the caller may not see and a stream that never opened one collapse to the same empty
 * answer. {@link Optional#map} drops a {@code null} mapping result on its own — that is what turns
 * "scope excluded it" into {@link Optional#empty()} here, with no extra filter.
 */
public final class DefaultUsageService implements UsageService {

    /** The default {@code limit} the API layer falls back to when the caller omits one. */
    public static final int DEFAULT_LIMIT = 50;

    /** The ceiling {@code limit} is silently clamped to, no matter how large the caller asks for. */
    static final int MAX_LIMIT = 500;

    private final AssetUsageRepositoryPort usageRepository;
    private final AssetRepositoryPort assetRepository;

    public DefaultUsageService(AssetUsageRepositoryPort usageRepository, AssetRepositoryPort assetRepository) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
    }

    @Override
    public List<UsageSummary> recent(VisibilityScope scope, AssetId assetIdOrNull, int limit) {
        Objects.requireNonNull(scope, "scope must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        int effectiveLimit = Math.min(limit, MAX_LIMIT);

        List<AssetUsage> usages = assetIdOrNull != null
                ? usageRepository.findRecentByAsset(assetIdOrNull, effectiveLimit)
                : usageRepository.findRecent(effectiveLimit);

        return usages.stream()
                .map(usage -> toSummary(usage, scope))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<UsageSummary> byStream(VisibilityScope scope, StreamId streamId) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(streamId, "streamId must not be null");
        return usageRepository.findByStream(streamId).map(usage -> toSummary(usage, scope));
    }

    /**
     * Resolves one usage's owning asset and folds it into a {@link UsageSummary}, or returns
     * {@code null} when {@code scope} may not see it — see this class's own javadoc for the
     * "asset no longer resolves" case.
     */
    private UsageSummary toSummary(AssetUsage usage, VisibilityScope scope) {
        Optional<Asset> asset = assetRepository.findById(usage.assetId());
        if (asset.isEmpty()) {
            return scope.isUnbounded() ? summaryOf(usage, "") : null;
        }
        if (!scope.includes(asset.get().id(), asset.get().ownership())) {
            return null;
        }
        return summaryOf(usage, asset.get().displayName());
    }

    private static UsageSummary summaryOf(AssetUsage usage, String assetName) {
        Long durationSeconds = usage.endedAt() == null
                ? null
                : Duration.between(usage.startedAt(), usage.endedAt()).getSeconds();
        return new UsageSummary(usage.id(), usage.assetId(), assetName, usage.startedAt(), usage.endedAt(),
                durationSeconds, usage.sampleCount());
    }
}
