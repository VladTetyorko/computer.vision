package com.drones.vision.domain.model;

/**
 * Which of the two tracking loops (docs/plans/done/TRACKING-PLAN.md §1) produced a particular {@link
 * Detection} on a particular frame.
 *
 * <p>{@code DETECTOR} — a full detector pass ran on this frame and produced this box. {@code
 * TRACKER} — the cheap per-frame visual tracker (Mode B, {@link TrackingMode#FOLLOW}) produced
 * this box without spending a detector pass. Pure marker, no behavior, no dedicated test (same
 * convention as {@code Capability}/{@code EventType}/{@code PixelFormat}).
 */
public enum DetectionSource {
    DETECTOR,
    TRACKER
}
