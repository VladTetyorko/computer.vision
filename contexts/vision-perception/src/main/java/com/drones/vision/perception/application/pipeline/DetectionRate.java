package com.drones.vision.perception.application.pipeline;

import java.time.Duration;
import java.util.Objects;

/**
 * Why this stream is detecting at the rate it is — the rate half of the picture {@link
 * PipelineLatency} gives the cost half of (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1).
 *
 * <h2>Why it is a separate record</h2>
 * {@link PipelineLatency#effectiveFps()} already reports the rate boxes <i>arrive</i> at, and the
 * first measurement taken with it (docs/conclusions/CV-RATE-BUDGET.md &sect;3) showed 7.58 against a
 * configured 10 — but it could not say <b>why</b>, because a completion rate cannot distinguish
 * "the source never delivered a frame to sample" from "the sample was thrown away at the in-flight
 * bound". Those two have opposite fixes. This record separates them by counting the sampler's own
 * decisions, so a shortfall names its own cause instead of inviting an argument.
 *
 * <h2>Reading it</h2>
 * {@link #targetFps()} is what the sampler aimed for, {@link #submittedFps()} what it achieved.
 * When they differ, exactly one of the three counters below is non-zero and says which loss it was:
 * a starving source ({@link #missedDeadlines()}), a saturated detector ({@link #droppedInFlight()}),
 * or a CV outage ({@link #droppedOutage()}).
 *
 * @param window           how far back the counters reach
 * @param sourceFps        measured frame arrival rate of the video source; the hard ceiling on any
 *                         achievable detection rate, and {@code 0} before the first measurement
 * @param targetFps        the rate the sampler is currently aiming for — the operator's {@code
 *                         inferenceFps}, raised to {@code followFps} in FOLLOW, and raised further
 *                         by the adaptive loop when the tracked target demands it
 * @param demandFps        the rate the tracked target's motion asked for before any ceiling was
 *                         applied (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;2); {@code 0} when
 *                         nothing is tracked. {@code demandFps > targetFps} means the stream is
 *                         capacity-limited, not configuration-limited — the one comparison that
 *                         separates "raise the ceiling" from "shrink the round trip"
 * @param submittedFps     frames actually handed to the detection port per second, over the window
 * @param submitted        frames handed to the detection port in the window. In pull mode
 *                         (docs/plans/done/MEDIA-SOT-PLAN.md &sect;7) this counts every {@code
 *                         DetectionResult} received rather than a local sampler decision — pull mode
 *                         delivers exactly one per inferred frame, so the count is direct, not derived
 *                         — and is cumulative since the stream started rather than windowed, matching
 *                         {@link #droppedInFlight} so {@link #due()}/{@link #dropRatio()} compare two
 *                         figures measured the same way. Before docs/conclusions/MEDIA-SOT-RESULTS.md
 *                         &sect;6's fix this was hard-coded {@code 0} in pull mode, which pinned {@link
 *                         #dropRatio()} at {@code 1.0} for any pull stream reporting even one drop
 * @param droppedInFlight  samples discarded because {@code maxInFlightInferences} were outstanding —
 *                         the detector is slower than the requested rate. In pull mode (docs/plans/done/MEDIA-SOT-PLAN.md
 *                         &sect;7) this is the worker's own {@code dropped_frames} — the latest-wins
 *                         discards its decode loop counts, the pull analogue of an in-flight drop
 * @param droppedOutage    samples withheld during a detection outage's backoff; always {@code 0} in
 *                         pull mode — outage backoff is push-mode-only bookkeeping
 * @param missedDeadlines  sample deadlines no frame arrived in time to serve — the source is slower
 *                         than the requested rate, so no amount of detector capacity would help
 * @param transport        which loop counted these figures: {@link #TRANSPORT_PUSH} (the JVM's own
 *                         sampler) or {@link #TRANSPORT_PULL} (the worker's, self-reported and mirrored
 *                         here) — docs/plans/done/MEDIA-SOT-PLAN.md &sect;5.4/&sect;7. Also the reader's cue for which
 *                         definition {@code PipelineLatency#roundTripMillis*} is using, since the two
 *                         read models are always read together off the same stream
 * @param decodeMillisP50  median local decode cost the worker reported, milliseconds; {@code 0} in
 *                         push mode (there is no local decode step to report) and before a pull stream
 *                         has reported anything yet
 */
public record DetectionRate(Duration window, double sourceFps, double targetFps, double demandFps,
                             double submittedFps, long submitted, long droppedInFlight, long droppedOutage,
                             long missedDeadlines, String transport, double decodeMillisP50) {

    /** {@link #transport()} value for a stream the JVM samples and pushes frames from. */
    public static final String TRANSPORT_PUSH = "push";

    /** {@link #transport()} value for a stream whose worker pulls frames and reports its own rate. */
    public static final String TRANSPORT_PULL = "pull";

    public DetectionRate {
        Objects.requireNonNull(window, "window must not be null");
        if (!TRANSPORT_PUSH.equals(transport) && !TRANSPORT_PULL.equals(transport)) {
            throw new IllegalArgumentException(
                    "transport must be \"" + TRANSPORT_PUSH + "\" or \"" + TRANSPORT_PULL + "\", was " + transport);
        }
    }

    /**
     * The shape before {@link #transport()}/{@link #decodeMillisP50()} were added (docs/plans/done/MEDIA-SOT-PLAN.md
     * &sect;5.4, wave M5), kept as a convenience constructor defaulting them to {@link #TRANSPORT_PUSH}/{@code
     * 0} — every push-mode reading, byte-identical to before this pair existed. Same "N-1-arg
     * convenience ctor" idiom the domain records use.
     */
    public DetectionRate(Duration window, double sourceFps, double targetFps, double demandFps,
                          double submittedFps, long submitted, long droppedInFlight, long droppedOutage,
                          long missedDeadlines) {
        this(window, sourceFps, targetFps, demandFps, submittedFps, submitted, droppedInFlight, droppedOutage,
                missedDeadlines, TRANSPORT_PUSH, 0.0);
    }

    /**
     * @return the snapshot of a stream that has sampled nothing yet, still carrying the two figures
     *         that are known before any frame arrives. What a stream reports between start and its
     *         first sample, and immediately after a model re-arm.
     */
    public static DetectionRate empty(Duration window, double sourceFps, double targetFps) {
        return new DetectionRate(window, sourceFps, targetFps, 0.0, 0.0, 0L, 0L, 0L, 0L);
    }

    /** @return sample opportunities that reached the sampler — every deadline a frame did serve. */
    public long due() {
        return submitted + droppedInFlight + droppedOutage;
    }

    /**
     * @return the fraction of served deadlines that were thrown away, in {@code [0,1]}; {@code 0}
     *         when nothing was due. The single number to watch: anything above zero means the
     *         configured rate is not the delivered one — in both transports, and meaning the same
     *         thing in both (docs/conclusions/MEDIA-SOT-RESULTS.md &sect;6): frames the loop wanted to
     *         infer and could not, over frames it wanted to infer. A source running faster than the
     *         target rate is <b>not</b> counted here in either transport — that shows up as the gap
     *         between {@link #sourceFps()} and {@link #submittedFps()} instead, never as a drop.
     */
    public double dropRatio() {
        long due = due();
        return due == 0L ? 0.0 : (double) (droppedInFlight + droppedOutage) / due;
    }
}
