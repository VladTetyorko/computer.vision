package com.drones.vision.adapter.simulation;

/**
 * Framework-free tunables for {@link SimulatedVideoSource}'s synthetic frame generation.
 *
 * <p>These are the <em>fallback</em> values a {@link SimulatedVideoSource} instance uses whenever
 * a given {@link com.drones.vision.kernel.StreamDescriptor}'s {@code width}/{@code
 * height}/{@code fps} options are absent — a per-{@code open()} option always takes priority (see
 * {@link SimulatedVideoSource} for that lenient-parsing convention). Per
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3, this module never imports a {@code @ConfigurationProperties}
 * type; a later wave binds {@code vision.simulation.video.*} in {@code vision-app} and constructs
 * this record there.
 *
 * @param width  default frame width in pixels; must be positive
 * @param height default frame height in pixels; must be positive
 * @param fps    default target frames per second; must be positive
 */
public record VideoSettings(int width, int height, int fps) {

    static final int DEFAULT_WIDTH = 640;
    static final int DEFAULT_HEIGHT = 480;
    static final int DEFAULT_FPS = 15;

    public VideoSettings {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be positive: " + fps);
        }
    }

    /** The pre-extraction defaults (640x480 @15fps) — byte-identical to the literals they replace. */
    public static VideoSettings defaults() {
        return new VideoSettings(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FPS);
    }
}
