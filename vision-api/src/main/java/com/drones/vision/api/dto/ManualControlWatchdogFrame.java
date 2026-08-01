package com.drones.vision.api.dto;

/**
 * Server&rarr;client {@code /ws/manual-control} frame pushed when the session's watchdog
 * auto-releases it for input loss (docs/RC-CONTROL-PHASE1-PLAN.md §4). By the time this is sent
 * the session is already released and audited {@code WATCHDOG} server-side — the client must
 * re-{@code engage} to resume; there is no separate {@code released} frame for this path.
 *
 * @param type      always {@code "watchdog"}
 * @param timeoutMs the watchdog timeout that elapsed, echoing {@code
 *                  vision.rc.watchdog-timeout-ms}
 */
public record ManualControlWatchdogFrame(String type, long timeoutMs) {

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlWatchdogFrame(long timeoutMs) {
        this("watchdog", timeoutMs);
    }
}
