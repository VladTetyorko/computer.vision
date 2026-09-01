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
 * <p>Accepts 1..16 channels — MAVLink #70 itself extends to 18, but ArduPilot reads only channels
 * 1..16 (docs/plans/active/FLEET-RADIO-PLAN.md F17); offering 17/18 would be a lie {@link
 * ManualControlService} could never make true. {@link ManualControlService} populates every
 * channel a caller supplies, 1..16.
 *
 * <h2>Channels 9..16 do not share 1..8's sentinels on the wire (F4)</h2>
 * This record's own {@link #RELEASE}/{@link #IGNORE} constants are the <b>domain</b> sentinels —
 * "give this channel back to the RC radio" / "say nothing about this channel" — and mean the same
 * thing regardless of channel number. But MAVLink's own {@code RC_CHANNELS_OVERRIDE} spec encodes
 * "release" differently once past channel 8: for channels 1..8, wire {@code 0} is release and
 * wire {@code 65535} is ignore; for channels 9..16, wire {@code 0} <em>or</em> {@code 65535} both
 * mean ignore, and only {@code 65534} ({@link #EXTENSION_RELEASE}) means release. A caller that
 * reused channels 1..8's {@code RELEASE=0} verbatim on an extension channel would silently send
 * "ignore" instead of "release", leaving that channel latched at its last commanded value forever.
 * {@link #wireValue(int)} is the one place this asymmetry is resolved, so every other caller in
 * this module can reason about "release" as a single concept.
 *
 * @param microsByChannel one entry per RC channel, 1-based; length 1..16; each entry either within
 *                        {@code [MIN_MICROS, MAX_MICROS]} or equal to {@link #RELEASE}/{@link #IGNORE}
 */
public record RcChannels(List<Integer> microsByChannel) {

    /** Highest RC channel this record — and ArduPilot itself — recognises (F17). */
    public static final int MAX_CHANNELS = 16;

    /** Channels 1..8 use MAVLink #70's own base fields; 9..{@link #MAX_CHANNELS} are extension fields. */
    public static final int BASE_CHANNEL_COUNT = 8;

    /** "Release this channel back to the RC radio" — the domain sentinel; see {@link #wireValue(int)}
     *  for how channels past {@link #BASE_CHANNEL_COUNT} actually encode this on the wire. */
    public static final int RELEASE = 0;

    /** "Leave this channel unchanged" — 65535, MAVLink #70's own ignore sentinel, identical for every channel. */
    public static final int IGNORE = 0xFFFF;

    /**
     * The wire's own "release" sentinel for an <em>extension</em> channel (9..{@link
     * #MAX_CHANNELS}) — {@code UINT16_MAX - 1}. Never appears in {@link #microsByChannel()} itself
     * (this record only ever stores the domain {@link #RELEASE}); it is what {@link #wireValue(int)}
     * translates a domain {@link #RELEASE} into for channel 9 and above.
     */
    public static final int EXTENSION_RELEASE = 0xFFFE;

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
     * The value for one RC channel, encoded exactly as MAVLink's own wire format expects for
     * <em>that channel's own range</em> — the one call every frame-builder needs instead of
     * re-deriving the 1..8-vs-9..16 sentinel asymmetry itself (see this record's own class javadoc).
     *
     * @param oneBased the RC channel number, 1-based
     * @return {@link #IGNORE} if {@code oneBased} is beyond this frame's own length (a "short" frame
     *         pads the rest with ignore); otherwise the channel's stored value, with a domain
     *         {@link #RELEASE} re-encoded as {@link #EXTENSION_RELEASE} for channel {@link
     *         #BASE_CHANNEL_COUNT}+1 and above
     */
    public int wireValue(int oneBased) {
        if (oneBased < 1) {
            throw new IllegalArgumentException("RcChannels channel must be >= 1: " + oneBased);
        }
        if (oneBased > microsByChannel.size()) {
            return IGNORE;
        }
        int value = microsByChannel.get(oneBased - 1);
        return value == RELEASE && oneBased > BASE_CHANNEL_COUNT ? EXTENSION_RELEASE : value;
    }

    /** A frame where every one of {@code channelCount} channels is {@link #IGNORE}. */
    public static RcChannels allIgnore(int channelCount) {
        if (channelCount < 1 || channelCount > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "RcChannels channelCount must be within [1," + MAX_CHANNELS + "]: " + channelCount);
        }
        return new RcChannels(Collections.nCopies(channelCount, IGNORE));
    }

    /**
     * A frame that releases every one of {@code channelCount} channels back to the RC radio —
     * stored as the domain {@link #RELEASE} sentinel throughout; {@link #wireValue(int)} is what
     * turns that into {@link #EXTENSION_RELEASE} for the channels past {@link #BASE_CHANNEL_COUNT}
     * once this frame actually reaches the wire.
     */
    public static RcChannels released(int channelCount) {
        if (channelCount < 1 || channelCount > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "RcChannels channelCount must be within [1," + MAX_CHANNELS + "]: " + channelCount);
        }
        return new RcChannels(Collections.nCopies(channelCount, RELEASE));
    }
}
