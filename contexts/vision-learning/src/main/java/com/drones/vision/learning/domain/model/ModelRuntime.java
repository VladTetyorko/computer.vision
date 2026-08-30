package com.drones.vision.learning.domain.model;

/**
 * Which runtime one {@link CvModelRecord} actually executes on (docs/plans/active/CV-SETTINGS-PLAN.md
 * §1.3/§3.2) — {@code OPENVINO} is materially faster than {@code PYTORCH} on the Intel-only,
 * no-CUDA inference host this platform targets, but today only {@code yolo11n.pt} ships a baked
 * OpenVINO IR; every other checkpoint runs plain PyTorch CPU. The honest speed signal a model
 * picker shows, not a preference — no silent "as fast as" substitution. Pure marker, no behavior,
 * no dedicated test (same convention as {@link JobState}).
 */
public enum ModelRuntime {
    PYTORCH,
    OPENVINO
}
