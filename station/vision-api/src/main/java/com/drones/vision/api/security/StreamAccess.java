package com.drones.vision.api.security;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The live-operations authority seam (docs/plans/active/LIVE-SCOPE-PLAN.md §2.1) — {@link
 * StreamController}'s handlers deal in {@link StreamId}/{@link DeviceId} and {@code
 * StreamService} knows nothing about assets or ownership, while the device&rarr;asset&rarr;owner
 * mapping lives in warehouse ({@link AssetRepositoryPort#findByDeviceId}). Composing the two to
 * answer "may this caller reach this stream?" is vision-api's job precisely because doing it
 * inside a context module would add a context&rarr;context edge for a concern (authorization) that
 * is not a domain concept — see the plan's §2.1 diagram.
 *
 * <h2>Visibility, not administer-authority, gates every one of these calls</h2>
 * A stream start/stop/live-config-write reads exactly like a management action, but {@link
 * VisibilityScope#canManage(com.drones.vision.kernel.Ownership)} is deliberately {@code false} for
 * every {@link VisibilityScope.Kind#ASSIGNED_ASSETS} scope <em>regardless of the asset</em> — its
 * own javadoc states plainly that seeing-and-flying the assigned aircraft "is the whole of a
 * pilot's authority". Gating a stream write on {@code canManage} would therefore 403 a PILOT
 * starting or stopping their <em>own</em> assigned stream, which is exactly the cockpit
 * LIVE-SCOPE-PLAN.md's own §2.2 table says must keep working ("a PILOT may still start+stop their
 * own assigned asset"). Since {@code canManage} and {@link VisibilityScope#includes} agree for
 * every other {@code Kind} (both {@code true} for {@code UNBOUNDED}; identical group-membership
 * test for {@code GROUPS}), the only sound reading of the plan's table — the one that does not
 * contradict its own "may still" column — is that stream reads and stream writes are gated on the
 * same predicate: {@link VisibilityScope#includes}. This class therefore exposes one check, used
 * by all eight {@link StreamController} handlers alike; there is no separate "write" method to
 * call by mistake.
 *
 * <h2>Unknown/not-currently-running streams</h2>
 * {@link #requireVisible(StreamId)} can only judge visibility for a stream this instance currently
 * has running (it resolves {@code streamId} to a {@link DeviceId} via {@link
 * StreamService#streams()}, the same list {@code GET /api/streams} is built from). For a
 * stream id that is unknown or already stopped, there is nothing to check against — the guard is a
 * no-op, and the handler's own pre-existing "unknown stream" handling (a 404 for {@code
 * config}/{@code snapshot}/{@code updateConfig}, an empty/idempotent result for {@code
 * tracks}/{@code detections}/{@code stop}) runs unchanged. This leaks nothing: those responses
 * already say nothing ownership-specific.
 *
 * <h2>Unowned devices are not a backdoor</h2>
 * A device that belongs to no asset at all ({@link AssetRepositoryPort#findByDeviceId} empty) has
 * no {@code Ownership} to test {@link VisibilityScope#includes} against. Rather than fail open
 * (visible to everyone) or fail the request outright, such a stream/device is visible only to a
 * caller whose scope {@link VisibilityScope#canAdminister()} — the same "deployment-global, no
 * group boundary" gate {@code AssetController#create} uses, since an unowned device is squarely a
 * fleet-administration concern, not any one group's.
 */
@Component
public final class StreamAccess {

    private final StreamService streamService;
    private final AssetRepositoryPort assetRepositoryPort;
    private final CurrentUser currentUser;

    public StreamAccess(StreamService streamService, AssetRepositoryPort assetRepositoryPort,
                         CurrentUser currentUser) {
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.assetRepositoryPort =
                Objects.requireNonNull(assetRepositoryPort, "assetRepositoryPort must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Guards a device-level operation (today, only {@link StreamController#start}) directly against
     * the device the caller named, before any stream exists for it.
     *
     * @param deviceId the device the caller wants to start a stream on
     * @throws NoSuchElementException if the caller's scope may not reach this device's asset — the
     *                                 same 404 an unknown device already produces, so existence is
     *                                 never revealed
     */
    public void requireVisible(DeviceId deviceId) {
        if (!visible(deviceId)) {
            throw new NoSuchElementException("Unknown device: " + deviceId.value());
        }
    }

    /**
     * Guards a stream-level read or write. A no-op when {@code streamId} does not currently name a
     * running stream (see class javadoc) — otherwise throws exactly as {@link
     * #requireVisible(DeviceId)} does for the stream's device.
     *
     * @param streamId the stream the caller is about to read or change
     * @throws NoSuchElementException if the stream is currently running on a device whose asset the
     *                                 caller's scope may not reach
     */
    public void requireVisible(StreamId streamId) {
        Optional<DeviceId> deviceId = deviceIdOf(streamId);
        if (deviceId.isPresent() && !visible(deviceId.get())) {
            throw new NoSuchElementException("Unknown or stopped stream: " + streamId.value());
        }
    }

    /**
     * Filters {@code streams} down to the ones the caller's scope may reach — the read half of
     * {@link StreamController#list}, which must narrow the fleet-wide list rather than all-or-
     * nothing 403 it (docs/plans/active/LIVE-SCOPE-PLAN.md §2.2).
     *
     * @param streams the candidate streams, as reported by {@link StreamService#streams()}
     * @return {@code streams}, filtered to the ones visible to {@link CurrentUser#scope()}
     */
    public List<ActiveStream> filterVisible(List<ActiveStream> streams) {
        return streams.stream().filter(s -> visible(s.deviceId())).toList();
    }

    private Optional<DeviceId> deviceIdOf(StreamId streamId) {
        return streamService.streams().stream()
                .filter(s -> s.streamId().equals(streamId))
                .map(ActiveStream::deviceId)
                .findFirst();
    }

    private boolean visible(DeviceId deviceId) {
        VisibilityScope scope = currentUser.scope();
        Optional<Asset> asset = assetRepositoryPort.findByDeviceId(deviceId);
        return asset.map(a -> scope.includes(a.id(), a.ownership())).orElseGet(scope::canAdminister);
    }
}
