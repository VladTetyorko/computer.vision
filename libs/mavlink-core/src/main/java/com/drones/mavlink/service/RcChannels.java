package com.drones.mavlink.service;

import java.util.Collections;
import java.util.List;

/**
 * RC channel override values in microseconds, 1-based (index 0 is channel 1) — the payload
 * {@link ManualControlService} relays as a MAVLink {@code RC_CHANNELS_OVERRIDE} (#70) frame.
 *
 * <p>This is a deliberate, small duplication of {@code vision-flight}'s own domain
 * {@code RcChannels} record: this module carries zero project dependencies (plan D2), so it cannot
 * import a context module's type, even one this shaped. A future {@code adapter-mavlink} caller
 * (W4) translates between the two at the L4/L5 boundary — see this module's MODULE.md.
 *
 * <p>Accepts 1..18 channels (MAVLink #70's own extent), though {@link ManualControlService} itself
 * only ever populates channels 1..8 from a caller-supplied {@code RcChannels} — channels 9..18 are
 * always sent as {@link #IGNORE} (v1 scope; see that class's own javadoc).
 *
 * @param microsByChannel one entry per RC channel, 1-based; length 1..18; each entry either within
 *                        {@code [MIN_MICROS, MAX_MICROS]} or equal to {@link #RELEASE}/{@link #IGNORE}
 */
public record RcChannels(List<Integer> microsByChannel) {

    /** "Release this channel back to the RC radio" (channels 1..8 only). */
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
     * @param oneBased the RC channel number, 1-based
     * @return {@link #IGNORE} if {@code oneBased} is beyond this frame's own length (a "short" frame
     *         pads the rest with ignore), else the channel's value
     */
    public int channelOrIgnore(int oneBased) {
        if (oneBased < 1) {
            throw new IllegalArgumentException("RcChannels channel must be >= 1: " + oneBased);
        }
        return oneBased <= microsByChannel.size() ? microsByChannel.get(oneBased - 1) : IGNORE;
    }

    /** A frame where every one of {@code channelCount} channels is {@link #IGNORE}. */
    public static RcChannels allIgnore(int channelCount) {
        if (channelCount < 1 || channelCount > 18) {
            throw new IllegalArgumentException("RcChannels channelCount must be within [1,18]: " + channelCount);
        }
        return new RcChannels(Collections.nCopies(channelCount, IGNORE));
    }

    /** A frame that releases every one of {@code channelCount} channels back to the RC radio. */
    public static RcChannels released(int channelCount) {
        if (channelCount < 1 || channelCount > 18) {
            throw new IllegalArgumentException("RcChannels channelCount must be within [1,18]: " + channelCount);
        }
        return new RcChannels(Collections.nCopies(channelCount, RELEASE));
    }
}
