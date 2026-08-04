package com.drones.vision.api.demo;

/**
 * How much data one press of the demo button should create.
 *
 * <p>Deliberately a demo-package record rather than a {@code …api.dto} one: the wire shape
 * ({@code DemoSeedRequest}) is nullable-everywhere so a caller can post an empty body, whereas this
 * is the resolved, already-clamped plan {@link DemoScenario} works from. The controller maps
 * between them.
 *
 * @param assets       how many simulated assets to create
 * @param users        how many demo users to create
 * @param startStreams how many of the created assets to put on the air
 */
public record DemoPlan(int assets, int users, int startStreams) {

    /** Upper bound on any single count — a demo press must never be able to spawn an unbounded fleet. */
    public static final int MAX = 50;

    /** What the green button asks for when the request carries no numbers of its own. */
    public static final DemoPlan DEFAULT = new DemoPlan(4, 4, 2);

    public DemoPlan {
        assets = clamp(assets);
        users = clamp(users);
        startStreams = Math.min(clamp(startStreams), assets);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX, value));
    }
}
