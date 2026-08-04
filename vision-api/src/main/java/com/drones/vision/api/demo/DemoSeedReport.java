package com.drones.vision.api.demo;

import java.util.List;

/**
 * What one seed run actually created — the demo-package counterpart of {@code DemoSeedResponse}.
 *
 * <p>{@link #problems()} is the reason every step of {@link DemoScenario#seed} is individually
 * fault-tolerant: a demo that half-fails (mediamtx down, so three stream starts threw) still
 * reports the nine things that worked instead of collapsing into one 500. Each entry is a short,
 * already-human-readable line such as {@code "stream Falcon 03: publisher unreachable"}.
 *
 * @param assetNames     display names of the assets created, in creation order
 * @param usernames      usernames of the users created, in creation order
 * @param assignments    how many pilot&rarr;asset assignments were granted
 * @param zones          how many geofence zones were created (0 when they already existed)
 * @param marks          how many tactical marks were dropped
 * @param streamsStarted how many assets were actually put on the air
 * @param videosUsed     file names of the videos backing the created assets, deduplicated
 * @param problems       one line per step that failed; empty on a clean run
 */
public record DemoSeedReport(List<String> assetNames, List<String> usernames, int assignments, int zones,
                             int marks, int streamsStarted, List<String> videosUsed, List<String> problems) {

    public DemoSeedReport {
        assetNames = List.copyOf(assetNames);
        usernames = List.copyOf(usernames);
        videosUsed = List.copyOf(videosUsed);
        problems = List.copyOf(problems);
    }
}
