package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.port.PulledDetectionPort;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Deployment-wide pull-mode wiring (docs/plans/done/MEDIA-SOT-PLAN.md wave M5, switch B — {@code
 * vision.cv.frame-transport}) — supplied once to {@code DefaultStreamService}'s constructor, the same
 * "one settings object in, one binding per stream out" shape {@link StreamPipelineSettings} already
 * has for video-source supervision. {@code null} on that constructor means push mode for every stream
 * this service starts, exactly as before this wave; non-null switches every stream it starts to the
 * pull-mode detection driver (D12's §5.5 note: B is one deployment-wide switch, not a per-device one —
 * only proxying video, switch A, is decided per device).
 *
 * @param port      the driven port {@code DefaultStreamService#start} calls {@link
 *                  PulledDetectionPort#open} on (lazily, inside a {@link SupervisedPublisher}'s
 *                  opener) for every stream it starts
 * @param rtspBase  the base RTSP URL the <b>worker</b> dials (docs/plans/done/MEDIA-SOT-PLAN.md &sect;5.5,
 *                  {@code vision.cv.pull.rtsp-base}) — deliberately not the JVM-facing publish base:
 *                  a remote worker needs the host's LAN address, not {@code localhost}. Joined with
 *                  {@code streamId.value()} (D2: the mediamtx path name) to build each pull's {@code
 *                  sourceUrl}
 * @param wallClock wall-clock "now" source handed to every {@link PullDetectionBinding} this service
 *                  builds; injectable so a test can drive it deterministically instead of {@link
 *                  Instant#now()}
 */
public record PullDetectionSettings(PulledDetectionPort port, URI rtspBase, Supplier<Instant> wallClock) {

    public PullDetectionSettings {
        Objects.requireNonNull(port, "port must not be null");
        Objects.requireNonNull(rtspBase, "rtspBase must not be null");
        Objects.requireNonNull(wallClock, "wallClock must not be null");
    }

    /**
     * Convenience constructor defaulting {@link #wallClock()} to {@link Instant#now()} — what
     * production wiring wants; a test that needs determinism uses the canonical constructor instead.
     */
    public PullDetectionSettings(PulledDetectionPort port, URI rtspBase) {
        this(port, rtspBase, Instant::now);
    }
}
