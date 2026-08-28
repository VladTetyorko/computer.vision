package com.drones.vision.flight.domain.model;

import java.util.Collections;
import java.util.List;

/**
 * RC channel override values in microseconds, 1-based (index 0 in {@link #microsByChannel()} is
 * RC channel 1) — the payload {@link com.drones.vision.flight.domain.port.ManualControlPort} relays
 * to the aircraft as a MAVLink {@code RC_CHANNELS_OVERRIDE} (#70) frame
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §1/§5).
 *
 * <p>The record accepts 1..16 channels — MAVLink #70 itself extends to 18, but ArduPilot reads only
 * channels 1..16 (docs/plans/active/FLEET-RADIO-PLAN.md F17); a {@link ControlProfile} may bind any
 * of them, mode switch/lights/winch on an aux channel included. Every entry is either a real
 * microsecond pulse width in {@code [MIN_MICROS, MAX_MICROS]}, or one of the two sentinels {@link
 * #RELEASE} / {@link #IGNORE}.
 *
 * <h2>Channels 9..16 do not share 1..8's release sentinel on the wire (F4)</h2>
 * {@link #RELEASE}/{@link #IGNORE} here are <b>domain</b> sentinels — "release this channel back to
 * the RC radio" / "say nothing about this channel" — and mean the same thing for every channel this
 * record can hold. MAVLink's own wire encoding disagrees once past channel 8: for 1..8, wire {@code
 * 0} is release; for 9..16, wire {@code 0} <em>and</em> {@code 65535} both mean ignore, and only
 * {@code 65534} means release. This module never builds the actual MAVLink frame — that happens in
 * {@code adapter-mavlink}, which translates this record into {@code mavlink-core}'s own {@code
 * RcChannels} — so the sentinel translation itself lives there ({@code
 * com.drones.mavlink.service.RcChannels#wireValue}), not in this record. A caller of this port only
 * ever needs to know "release" as one concept.
 *
 * @param microsByChannel one entry per RC channel, 1-based; length 1..16; each entry either
 *                         within {@code [MIN_MICROS, MAX_MICROS]} or equal to {@link #RELEASE}/
 *                         {@link #IGNORE}; defensively copied
 */
public record RcChannels(List<Integer> microsByChannel) {

    /** Highest RC channel this record — and ArduPilot itself — recognises (F17). */
    private static final int MAX_CHANNELS = 16;

    /** "Release this channel back to the RC radio" for every channel (see the class javadoc for
     *  how this is actually encoded on the wire past channel 8). */
    public static final int RELEASE = 0;

    /** "Leave this channel unchanged" — 65535, MAVLink #70's own ignore sentinel. */
    public static final int IGNORE = 0xFFFF;

    /** Minimum real (non-sentinel) microsecond pulse width. */
    public static final int MIN_MICROS = 1000;

    /** Maximum real (non-sentinel) microsecond pulse width. */
    public static final int MAX_MICROS = 2000;

    public RcChannels {
        if (microsByChannel == null) {
            throw new IllegalArgumentException("RcChannels microsByChannel must not be null");
        }
        if (microsByChannel.isEmpty() || microsByChannel.size() > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "RcChannels microsByChannel length must be within [1," + MAX_CHANNELS + "]: "
                            + microsByChannel.size());
        }
        for (Integer micros : microsByChannel) {
            if (micros == null) {
                throw new IllegalArgumentException("RcChannels microsByChannel must not contain null entries");
            }
            if (micros != RELEASE && micros != IGNORE && (micros < MIN_MICROS || micros > MAX_MICROS)) {
                throw new IllegalArgumentException(
                        "RcChannels value must be RELEASE, IGNORE, or within [" + MIN_MICROS + "," + MAX_MICROS
                                + "]: " + micros);
            }
        }
        microsByChannel = List.copyOf(microsByChannel);
    }

    /**
     * The value for one RC channel.
     *
     * @param oneBased the RC channel number, 1-based
     * @return the microsecond value (or sentinel) for that channel
     * @throws IllegalArgumentException if {@code oneBased} is outside {@code [1,
     *                                   microsByChannel().size()]}
     */
    public int channel(int oneBased) {
        if (oneBased < 1 || oneBased > microsByChannel.size()) {
            throw new IllegalArgumentException(
                    "RcChannels channel must be within [1," + microsByChannel.size() + "]: " + oneBased);
        }
        return microsByChannel.get(oneBased - 1);
    }

    /**
     * A frame that releases every one of {@code channelCount} channels back to the RC radio.
     *
     * @param channelCount number of channels to release, 1..16
     * @return a frame of {@link #RELEASE} values, one per channel 1..{@code channelCount}
     * @throws IllegalArgumentException if {@code channelCount} is outside {@code [1,16]}
     */
    public static RcChannels released(int channelCount) {
        if (channelCount < 1 || channelCount > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "RcChannels channelCount must be within [1," + MAX_CHANNELS + "]: " + channelCount);
        }
        return new RcChannels(Collections.nCopies(channelCount, RELEASE));
    }
}
