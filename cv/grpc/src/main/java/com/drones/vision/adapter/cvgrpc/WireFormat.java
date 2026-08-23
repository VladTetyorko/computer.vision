package com.drones.vision.adapter.cvgrpc;

import java.util.Locale;

/**
 * How a downscaled frame reaches cv-service (docs/plans/done/CV-RATE-CONTROL-PLAN.md wave R3).
 *
 * <h2>What this actually buys</h2>
 * Measured on loopback (docs/conclusions/CV-RATE-BUDGET.md &sect;3), a 53.8 ms round trip contained
 * only 29 ms of inference: <b>roughly half of it was not inference at all</b>, and the JPEG encode
 * on this side plus the decode on cv-service's side is the bulk of that remainder. The wire has
 * carried {@code IMAGE_ENCODING_BGR24} since the first version of the contract and cv-service has
 * always decoded it; only this side unconditionally re-encoded, because the downscale path was
 * written when the only deployment was remote.
 *
 * <h2>Why the default is a rule rather than a value</h2>
 * Neither choice is right everywhere: 640&times;360 of raw BGR24 is about 691 KB against roughly
 * 40 KB of JPEG, which is free over loopback and indefensible over a radio link. {@link #AUTO}
 * therefore decides per deployment from the endpoint itself — the one fact that actually
 * distinguishes the two cases — instead of picking a default that is wrong for half of them.
 *
 * <p>{@code JPEG} frames from an MJPEG source are unaffected either way: they are already encoded
 * and are forwarded byte-identical, never transcoded.
 */
public enum WireFormat {

    /** Raw {@code BGR24} to a loopback endpoint, JPEG to anything else. The shipped default. */
    AUTO,

    /** Always JPEG-encode the downscaled frame — the smallest payload, at the cost of both codecs. */
    JPEG,

    /** Always send the downscaled frame raw — no encode, no decode, a much larger payload. */
    BGR24;

    /**
     * Resolves {@link #AUTO} against a gRPC authority; {@link #JPEG} and {@link #BGR24} answer for
     * themselves.
     *
     * <p>Loopback is decided by inspecting the host text, deliberately <b>not</b> by resolving it:
     * this runs while a channel is being constructed, and a DNS lookup there would let a slow
     * resolver stall application startup to decide a payload encoding. An unrecognizable or
     * {@code null} authority resolves to {@code JPEG} — the conservative answer, since the cost of
     * being wrong is wasted CPU one way and a saturated radio link the other.
     *
     * @param authority the channel's authority, e.g. {@code "localhost:50051"}; may be {@code null}
     * @return {@code JPEG} or {@code BGR24}, never {@code AUTO}
     */
    public WireFormat resolve(String authority) {
        if (this != AUTO) {
            return this;
        }
        return isLoopback(authority) ? BGR24 : JPEG;
    }

    private static boolean isLoopback(String authority) {
        if (authority == null || authority.isBlank()) {
            return false;
        }
        String host = authority.toLowerCase(Locale.ROOT).trim();
        if (host.startsWith("[")) { // bracketed IPv6, e.g. [::1]:50051
            int close = host.indexOf(']');
            host = close < 0 ? host.substring(1) : host.substring(1, close);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
        }
        return host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
    }

    /**
     * @param text a configured value, case-insensitive; blank or {@code null} means {@link #AUTO}
     * @throws IllegalArgumentException if {@code text} names no format
     */
    public static WireFormat parse(String text) {
        if (text == null || text.isBlank()) {
            return AUTO;
        }
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
