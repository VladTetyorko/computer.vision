package com.drones.vision.api.dto;

import com.drones.vision.api.demo.DemoPeople;
import com.drones.vision.api.demo.DemoSeedReport;

import java.util.List;

/**
 * Result of {@code POST /api/demo/seed} — what the press created, and what (if anything) failed.
 *
 * @param assets         how many simulated assets were created
 * @param users          how many demo users were created
 * @param assignments    how many pilot&rarr;asset assignments were granted
 * @param zones          how many geofence zones were created (0 when they already existed)
 * @param marks          how many tactical marks were dropped
 * @param streamsStarted how many assets were put on the air
 * @param assetNames     the created assets' call signs
 * @param usernames      the created users' usernames
 * @param password       the DEV-ONLY password every demo user shares, so the caller can log in as one
 * @param videosUsed     file names of the videos backing the fleet; empty when none were found
 * @param problems       one line per step that failed; empty on a clean run
 */
public record DemoSeedResponse(int assets, int users, int assignments, int zones, int marks, int streamsStarted,
                               List<String> assetNames, List<String> usernames,
                               String password, List<String> videosUsed,
                               List<String> problems) {

    /** Projects a {@link DemoSeedReport} onto the wire, deriving the counts from the reported lists. */
    public static DemoSeedResponse of(DemoSeedReport report) {
        return new DemoSeedResponse(report.assetNames().size(), report.usernames().size(), report.assignments(),
                report.zones(), report.marks(), report.streamsStarted(), report.assetNames(),
                report.usernames(), DemoPeople.PASSWORD, report.videosUsed(), report.problems());
    }
}
