package com.drones.vision.api.dto;

import java.util.List;

/**
 * {@code videoIntake} on {@code GET /api/discovery/status} (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C2) — absent from the response entirely when mediamtx
 * publish is unconfigured ({@code VisionPublishProperties#enabled()} is {@code false}), handled by
 * {@link DiscoveryStatusResponse}'s own {@code @JsonInclude(NON_NULL)} rather than this record's.
 *
 * @param pushPort   the port a camera pushes RTSP to (mediamtx's publish port)
 * @param pathPrefix the mediamtx path-name prefix a device push must live under to be reported as
 *                   a discovery candidate (e.g. {@code "ingest/"})
 * @param readyPaths currently-live mediamtx paths under {@code pathPrefix}, full name including the
 *                   prefix (e.g. {@code "ingest/smoke-cam"})
 */
public record VideoIntakeResponse(int pushPort, String pathPrefix, List<String> readyPaths) {
}
