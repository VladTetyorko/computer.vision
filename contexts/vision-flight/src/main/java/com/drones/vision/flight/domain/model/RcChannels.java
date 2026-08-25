package com.drones.vision.flight.domain.model;

import java.util.Collections;
import java.util.List;

/**
 * RC channel override values in microseconds, 1-based (index 0 in {@link #microsByChannel()} is
 * RC channel 1) — the payload {@link com.drones.vision.flight.domain.port.ManualControlPort} relays
 * to the aircraft as a MAVLink {@code RC_CHANNELS_OVERRIDE} (#70) frame
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §1/§5).
 *
 * <p>The record itself accepts 1..18 channels (MAVLink #70's own extent), but no {@link
 * ControlProfile} populates past channel 8 — channels 9..18 use a different,
 * ambiguous extension release sentinel this platform does not resolve yet (see the plan's Open
 * Questions §4). Every entry is either a real microsecond pulse width in {@code [MIN_MICROS,
 * MAX_MICROS]}, or one of the two sentinels {@link #RELEASE} / {@link #IGNORE}.
 *
 * @param microsByChannel one entry per RC channel, 1-based; length 1..18; each entry either
 *                         within {@code [MIN_MICROS, MAX_MICROS]} or equal to {@link #RELEASE}/
 *                         {@link #IGNORE}; defensively copied
 */
public record RcChannels(List<Integer> microsByChannel) {

    /** "Release this channel back to the RC radio" (channels 1..8 only — see the class javadoc). */
    public static final int RELEASE = 0;

    /** "Leave this channel unchanged" — 65535, MAVLink #70's own ignore sentinel. */
    public static final int IGNORE = 0xFFFF;

    /** Minimum real (non-sentinel) microsecond pulse width. */
    public static final int MIN_MICROS = 1000;

    /** Maximum real (non-sentinel) microsecond pulse width. */
    public static final int MAX_MICROS = 2000;

    /**
     * How many channels the manual-control relay actually puts on the wire today: {@code 1..8}
     * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §5's v1 scope).
     *
     * <p>Named here, in the domain, because it is not only the adapter's business: a
     * {@link ControlBinding} may legally target {@code [1,18]} — the MAVLink message has the fields
     * — but a binding above this number is stored, shown, and <b>never sent</b>. Anything offering
     * an operator a channel to pick must offer only channels that reach the aircraft, so it needs
     * this number rather than the validation range.
     *
     * <p>Raising it is not a one-line change: {@code RC_CHANNELS_OVERRIDE} uses a <em>different</em>
     * release sentinel for channels 9–18 ({@code 65534}, not {@code 0}) — see
     * docs/plans/active/OPERATOR-CONTROL-CONTEXT.md G13, still open.
     */
    public static final int RELAYED_CHANNELS = 8;

    public RcChannels {
        if (microsByChannel == null) {
            throw new IllegalArgumentException("RcChannels microsByChannel must not be null");
        }
        if (microsByChannel.isEmpty() || microsByChannel.size() > 18) {
            throw new IllegalArgumentException(
                    "RcChannels microsByChannel length must be within [1,18]: " + microsByChannel.size());
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
     * @param channelCount number of channels to release, 1..18
     * @return a frame of {@link #RELEASE} values, one per channel 1..{@code channelCount}
     * @throws IllegalArgumentException if {@code channelCount} is outside {@code [1,18]}
     */
    public static RcChannels released(int channelCount) {
        if (channelCount < 1 || channelCount > 18) {
            throw new IllegalArgumentException("RcChannels channelCount must be within [1,18]: " + channelCount);
        }
        return new RcChannels(Collections.nCopies(channelCount, RELEASE));
    }
}
