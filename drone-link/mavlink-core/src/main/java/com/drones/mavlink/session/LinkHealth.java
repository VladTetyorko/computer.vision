package com.drones.mavlink.session;

import com.drones.mavlink.PeerId;

import java.time.Instant;

/**
 * Per-peer link quality, tracked from observed {@code seq} accounting (plan §2.1: the spec defines
 * no drop-rate formula — see {@link DefaultLinkHealth} for ours, documented as ours, not standard).
 */
public interface LinkHealth {

    /** {@code id}'s current health. Never {@code null} — an unheard peer reports {@code connected == false}. */
    Health of(PeerId id);

    /**
     * @param peerId    identity of the peer this snapshot describes (FLEET-RADIO-PLAN.md D4) — carried
     *                  on the record itself so a consumer that has rolled several peers' health into
     *                  one collection (e.g. {@code Map<DeviceId, Health>}) never has to re-derive
     *                  "whose number is this" from context; {@code of(id)}'s own {@code id} argument
     * @param connected {@code true} if heard within the configured peer timeout of "now"
     * @param lastHeard {@code null} if this peer has never been heard from
     * @param received  frames received from this peer since it was first heard (or since it last
     *                  switched links — see {@link DefaultLinkHealth})
     * @param lost      frames inferred lost from {@code seq} gaps over the same window
     * @param dropRate  {@code lost / (received + lost)}, {@code 0.0} if nothing has been received yet
     */
    record Health(PeerId peerId, boolean connected, Instant lastHeard, long received, long lost, double dropRate) {
    }
}
