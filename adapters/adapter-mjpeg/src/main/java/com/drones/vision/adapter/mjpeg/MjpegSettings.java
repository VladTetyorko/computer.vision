package com.drones.vision.adapter.mjpeg;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Framework-free tunables for this module, one field per {@code vision.mjpeg.*} property key in
 * {@code docs/plans/active/LAYERING-REFACTOR-PLAN.md} §2.2. This record is plain (no Spring) per that plan's
 * §1.3 binding rule: a later wave adds {@code VisionMjpegProperties} in {@code vision-app} and
 * binds it onto this record — this module never imports that type.
 *
 * <p>Top-level fields configure the RX side ({@link MjpegVideoSource}); {@link Transmit} fields
 * configure the TX side ({@code MjpegFeedTransmitter} and its collaborators, {@link
 * MjpegHttpServerHost} and {@link MjpegViewerSession}).
 *
 * <p>{@link #defaults()} reproduces, field for field, every literal this module hardcoded before
 * this record existed — constructing a transmitter/source with {@link #defaults()} is
 * byte-identical in behavior to the pre-extraction code.
 *
 * @param readTimeout             RX connect-timeout fallback used when a {@link
 *                                com.drones.vision.kernel.StreamDescriptor}'s {@code
 *                                timeout} option is missing/blank/malformed; only bounds the
 *                                initial TCP connect, never the (intentionally unbounded) body
 *                                read — see {@code MjpegVideoSource}'s gotchas.
 * @param publisherBufferCapacity RX {@link java.util.concurrent.SubmissionPublisher} buffer
 *                                capacity (drop-newest backpressure beyond this depth)
 * @param closeJoinTimeout        RX {@code close()}'s bounded join on the per-stream read thread
 * @param transmit                TX-side tunables, see {@link Transmit}
 */
public record MjpegSettings(Duration readTimeout, int publisherBufferCapacity, Duration closeJoinTimeout,
                             Transmit transmit) {

    public MjpegSettings {
        Objects.requireNonNull(readTimeout, "readTimeout must not be null");
        requirePositive(readTimeout, "readTimeout");
        if (publisherBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "publisherBufferCapacity must be positive, got " + publisherBufferCapacity);
        }
        Objects.requireNonNull(closeJoinTimeout, "closeJoinTimeout must not be null");
        requirePositive(closeJoinTimeout, "closeJoinTimeout");
        Objects.requireNonNull(transmit, "transmit must not be null");
    }

    /** Reproduces every literal this module hardcoded before this record existed. */
    public static MjpegSettings defaults() {
        return new MjpegSettings(Duration.ofMillis(5_000L), 4, Duration.ofMillis(20_000L), Transmit.defaults());
    }

    private static void requirePositive(Duration duration, String fieldName) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be positive, got " + duration);
        }
    }

    /**
     * TX-side (file-loop transmit simulator) tunables.
     *
     * @param bindHost         loopback host the shared {@link com.sun.net.httpserver.HttpServer}
     *                         binds to
     * @param maxViewerThreads dispatch-pool cap for concurrent viewer connections; {@link
     *                         OptionalInt#empty()} reproduces today's unbounded {@code
     *                         Executors.newCachedThreadPool()} — there was never a literal cap to
     *                         preserve, so "absent" is itself the faithful default (same idiom as
     *                         {@code adapter-v4l2}'s optional driver-default fields)
     * @param jpegQuality      {@code javax.imageio} JPEG compression quality in {@code (0, 1]};
     *                         {@code 0.75} matches the standard JPEG writer's own implicit
     *                         default, which is what {@code ImageIO.write(image, "jpg", out)}
     *                         produced before this was made explicit/configurable
     * @param loop             default for a feed's per-{@code FeedSpec} {@code loop} option when
     *                         that option is absent
     * @param viewerJoinTimeout bounded join per viewer thread on {@code stop(FeedId)}/{@code
     *                          close()}
     */
    public record Transmit(String bindHost, OptionalInt maxViewerThreads, float jpegQuality, boolean loop,
                            Duration viewerJoinTimeout) {

        public Transmit {
            Objects.requireNonNull(bindHost, "bindHost must not be null");
            if (bindHost.isBlank()) {
                throw new IllegalArgumentException("bindHost must not be blank");
            }
            Objects.requireNonNull(maxViewerThreads, "maxViewerThreads must not be null");
            if (maxViewerThreads.isPresent() && maxViewerThreads.getAsInt() <= 0) {
                throw new IllegalArgumentException(
                        "maxViewerThreads must be positive when present, got " + maxViewerThreads.getAsInt());
            }
            if (jpegQuality <= 0f || jpegQuality > 1f) {
                throw new IllegalArgumentException("jpegQuality must be in (0, 1], got " + jpegQuality);
            }
            Objects.requireNonNull(viewerJoinTimeout, "viewerJoinTimeout must not be null");
            requirePositive(viewerJoinTimeout, "viewerJoinTimeout");
        }

        public static Transmit defaults() {
            return new Transmit("127.0.0.1", OptionalInt.empty(), 0.75f, true, Duration.ofMillis(5_000L));
        }
    }
}
