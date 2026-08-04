package com.drones.vision.api.dto;

import java.util.List;

/**
 * Result of {@code GET /api/demo} — the probe the console's demo button uses to decide whether to
 * render itself at all, plus what the seed would draw on.
 *
 * <p>{@code enabled} is always {@code true} here by construction: with {@code
 * vision.demo.enabled=false} the whole demo package is absent from the context, so the route 404s
 * rather than answering {@code false}. It is on the wire anyway so a client can treat "404" and
 * "enabled: false" identically without special-casing a status code.
 *
 * @param enabled       always {@code true} — see above
 * @param videosDirectory absolute path of the folder the fleet's footage is read from
 * @param videos        file names of the playable videos found directly in that folder
 */
public record DemoStatusResponse(boolean enabled, String videosDirectory, List<String> videos) {
}
