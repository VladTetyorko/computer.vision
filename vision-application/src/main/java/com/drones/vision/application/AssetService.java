package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;

import java.util.List;

/**
 * Everything the application does with assets — the user-facing "my drone", one or more devices
 * behind one name.
 *
 * <p>One interface, one implementation ({@link DefaultAssetService}). Streaming an asset lives
 * here rather than in a separate service because it is an operation <em>on an asset</em>: it
 * resolves which of the asset's devices to use, then delegates the mechanics to
 * {@link StreamService}.
 *
 * <p>Ownership is derived from the acting user at call time, never injected at construction.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface AssetService {

    /**
     * Creates an asset together with its device(s), owned by the acting user.
     *
     * <p>{@link AssetSpec#devices()} registers brand-new devices; {@link
     * AssetSpec#existingDeviceIds()} instead assigns already-registered, currently unowned devices
     * to the new asset in the same act — validated exactly like {@link #assignDevice} (must exist,
     * must not be soft-deleted, must not already belong to another asset). Both may be used
     * together; at least one device between the two is required (enforced by {@link AssetSpec}
     * itself).
     *
     * @param spec      what to create
     * @param ownership who will own it
     * @param actor     the user performing the creation
     * @return the created asset
     * @throws IllegalArgumentException if the category does not exist, or an {@code
     *                                   existingDeviceIds} entry is soft-deleted
     * @throws java.util.NoSuchElementException if an {@code existingDeviceIds} entry is unknown
     * @throws IllegalStateException            if an {@code existingDeviceIds} entry already
     *                                           belongs to another asset
     */
    Asset create(AssetSpec spec, Ownership ownership, UserId actor);

    /**
     * Lists assets that have not been soft-deleted, as summaries.
     *
     * @return an immutable snapshot
     */
    default List<AssetSummary> assets() {
        return assets(false);
    }

    /**
     * Lists assets as summaries, optionally including soft-deleted ones.
     *
     * @param includeDeleted whether to include assets in {@link LifecycleState#DELETED}
     * @return an immutable snapshot
     */
    List<AssetSummary> assets(boolean includeDeleted);

    /**
     * Lists the assets a given visibility scope may see, as summaries (docs/U-SCOPE-PLAN.md, U-e
     * slice 2, feature 1).
     *
     * <p>Filters the unscoped {@link #assets(boolean)} result by {@link VisibilityScope#includes};
     * an {@link VisibilityScope#unbounded()} scope therefore returns precisely the unscoped result —
     * the slice's guardrail. Internal/system callers that must see everything keep using the
     * unscoped {@link #assets(boolean)} directly.
     *
     * @param scope          what the acting user may see
     * @param includeDeleted whether to include soft-deleted assets
     * @return an immutable snapshot, filtered to {@code scope}
     */
    List<AssetSummary> assets(VisibilityScope scope, boolean includeDeleted);

    /**
     * Assembles the full detail view of one asset.
     *
     * @param id the asset id
     * @return summary, devices and recent usages
     * @throws java.util.NoSuchElementException if no asset has that id
     */
    AssetDetails details(AssetId id);

    /**
     * Assembles the full detail view of one asset the given scope may see (docs/U-SCOPE-PLAN.md,
     * U-e slice 2, feature 1).
     *
     * <p>When the asset exists but is outside {@code scope}, this throws {@link
     * java.util.NoSuchElementException} — the same 404 as an unknown id, deliberately, so a scoped
     * read never reveals the existence of an asset outside the caller's scope (a 403 would). An
     * {@link VisibilityScope#unbounded()} scope behaves exactly as {@link #details(AssetId)}.
     *
     * @param scope what the acting user may see
     * @param id    the asset id
     * @return summary, devices and recent usages
     * @throws java.util.NoSuchElementException if no asset has that id, or it is outside {@code scope}
     */
    AssetDetails details(VisibilityScope scope, AssetId id);

    /**
     * Applies a partial edit.
     *
     * @param id    the asset to edit
     * @param edit  the fields to change
     * @param actor the user performing the edit
     * @return the updated asset
     * @throws java.util.NoSuchElementException if no asset has that id
     * @throws IllegalArgumentException         if the requested category does not exist
     */
    Asset update(AssetId id, AssetEdit edit, UserId actor);

    /**
     * Moves an asset to a lifecycle state, stopping its streams when it leaves service.
     *
     * <p>Idempotent. A soft-deleted asset may be restored to {@link LifecycleState#DEACTIVATED}
     * but not straight to {@link LifecycleState#ACTIVE} — coming back from deletion should never
     * put a source back on the air in the same breath.
     *
     * @param id    the asset to move
     * @param state the state to move it to
     * @param actor the user performing the change
     * @return the asset as persisted
     * @throws java.util.NoSuchElementException if no asset has that id
     * @throws IllegalStateException            if activating an asset that is currently deleted
     */
    Asset setState(AssetId id, LifecycleState state, UserId actor);

    /**
     * Soft-deletes an asset and its devices: stops streams, hides them, keeps usages and telemetry.
     *
     * @param id    the asset to delete
     * @param actor the user performing the deletion
     * @return what the deletion affected, and what it preserved
     * @throws java.util.NoSuchElementException if no asset has that id
     */
    AssetDeletion delete(AssetId id, UserId actor);

    /**
     * Starts streaming from one of the asset's devices.
     *
     * @param id     the asset to stream
     * @param device which device to use, or {@code null} to resolve the asset's only active
     *               video-capable source
     * @param config pipeline settings for this stream
     * @return the new stream's id
     * @throws java.util.NoSuchElementException if no asset has that id
     * @throws IllegalArgumentException         if the device does not belong to the asset, or which
     *                                          device to use is ambiguous
     * @throws IllegalStateException            if the asset is not in service
     */
    StreamId startStream(AssetId id, DeviceId device, PipelineConfig config);

    /**
     * Stops every stream this asset's devices are running. A no-op for an unknown asset.
     *
     * @param id the asset to stop
     */
    void stopStream(AssetId id);

    /**
     * Assigns an existing, unowned device to this asset (docs/CYCLES-PLAN.md §8's pinned
     * contract).
     *
     * @param id       the asset to assign the device to
     * @param deviceId the device to assign
     * @param actor    the user performing the assignment
     * @return the updated asset
     * @throws java.util.NoSuchElementException if no asset or device has that id
     * @throws IllegalArgumentException         if the device is soft-deleted
     * @throws IllegalStateException            if the device already belongs to an asset (naming it)
     */
    Asset assignDevice(AssetId id, DeviceId deviceId, UserId actor);

    /**
     * Removes one of this asset's devices, leaving the device itself untouched (docs/CYCLES-PLAN.md
     * §8's pinned contract).
     *
     * @param id       the asset to unassign the device from
     * @param deviceId the device to unassign
     * @param actor    the user performing the change
     * @return the updated asset
     * @throws java.util.NoSuchElementException if no asset has that id
     * @throws IllegalArgumentException         if the device does not belong to this asset
     * @throws IllegalStateException            if the device is the asset's last remaining one
     */
    Asset unassignDevice(AssetId id, DeviceId deviceId, UserId actor);
}
