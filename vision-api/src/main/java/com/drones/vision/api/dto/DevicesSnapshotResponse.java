package com.drones.vision.api.dto;

import java.util.List;

/**
 * Payload of the {@code devices} {@code GET /api/live} topic (docs/plans/done/REALTIME-PLAN.md §4, extended
 * for the fleet/warehouse UI) — the combined device-list + active-stream-list snapshot {@code
 * FleetStore} (vision-web) otherwise polls via {@code GET /api/devices}+{@code GET /api/streams}
 * every 5s.
 *
 * <p>Both lists travel in one envelope, under the channel's one shared, resumable {@code seq}
 * (see {@code LiveEnvelopeResponse}'s own javadoc) — deliberately not two separate topics/seqs,
 * so a viewer can never observe a device list and an active-stream list that were snapshotted at
 * different moments (e.g. a stream already stopped server-side but its device still shown as
 * live, or vice versa).
 *
 * <p>Always a full snapshot, like {@code fleet}'s own {@code List<AssetSummaryResponse>} payload —
 * never a diff.
 *
 * @param devices every non-archived device, mirroring {@code GET /api/devices}'s default
 *                (({@code includeDeleted=false})
 * @param streams every stream currently active on this instance, mirroring {@code GET /api/streams}
 */
public record DevicesSnapshotResponse(List<DeviceResponse> devices, List<ActiveStreamResponse> streams) {
}
