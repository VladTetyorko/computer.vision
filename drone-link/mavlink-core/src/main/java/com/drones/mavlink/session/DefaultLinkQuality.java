package com.drones.mavlink.session;

import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.LinkId;

import io.dronefleet.mavlink.common.RadioStatus;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscribes to inbound {@code RADIO_STATUS} (#109) frames whose source component is {@code
 * MAV_COMP_ID_TELEMETRY_RADIO} (68) or {@code MAV_COMP_ID_RADIO}/{@code RADIO2}/{@code RADIO3}
 * (110-112) — the well-known component ids a telemetry radio (ground or air side) reports its own
 * link quality under, distinct from the autopilot's own component id — and keeps the latest
 * reading per {@link LinkId} (the link the frame arrived <b>on</b>, not the peer it is about:
 * RADIO_STATUS is a radio-to-radio fact, and this module has no other way to attribute it to a
 * specific carrier once several links exist).
 *
 * <p>Constructed with a {@link Dispatcher} and subscribes once, in the constructor — no change to
 * {@link MavlinkSession}, {@code onFrame}, or any existing call site (LINK-PAIRING-PLAN.md §3.1 QA
 * pass: {@link MavlinkSession#dispatcher()}/{@link Dispatcher#subscribe} were already public API).
 * The handler runs synchronously on the RX thread per {@link Dispatcher}'s own contract — a plain
 * map write, never blocking.
 */
public final class DefaultLinkQuality implements LinkQuality {

    private static final Set<Integer> RADIO_COMPONENT_IDS = Set.of(68, 110, 111, 112);

    private final Map<LinkId, Quality> byLink = new ConcurrentHashMap<>();

    public DefaultLinkQuality(Dispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.subscribe(
                MessageFilter.and(MessageFilter.type(RadioStatus.class), DefaultLinkQuality::fromRadioComponent),
                this::onRadioStatus);
    }

    @Override
    public Quality of(LinkId id) {
        Objects.requireNonNull(id, "id");
        return byLink.get(id);
    }

    private void onRadioStatus(MavFrame frame) {
        RadioStatus status = frame.as(RadioStatus.class);
        Quality quality = new Quality(frame.link(), frame.receivedAt(), status.rssi(), status.remrssi(),
                status.noise(), status.rxerrors(), status.fixed() != 0);
        byLink.put(frame.link(), quality);
    }

    private static boolean fromRadioComponent(MavFrame frame) {
        return RADIO_COMPONENT_IDS.contains(frame.header().component().value());
    }
}
