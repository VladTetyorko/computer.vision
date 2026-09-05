package com.drones.vision.perception.domain.model;

/**
 * Lifecycle of an operator-issued {@code FOLLOW} lock (docs/plans/active/TRACK-FOLLOW-PLAN.md
 * &sect;3.1) — mirrors cv-service's own five lock states ({@code lock.py}, {@code session.py})
 * one-for-one; no new semantics are invented here.
 *
 * <table>
 *   <caption>wire condition each value answers to</caption>
 *   <tr><th>Value</th><th>Wire condition</th></tr>
 *   <tr><td>{@link #REQUESTING}</td><td>a lock was issued; {@code lockedTrackId} is still {@code 0}</td></tr>
 *   <tr><td>{@link #HOLDING}</td><td>{@code lockedTrackId == trackId}; the bound track's newest
 *       {@code TrackRef.source() == DetectionSource.DETECTOR}</td></tr>
 *   <tr><td>{@link #COASTING}</td><td>{@code lockedTrackId == trackId}; newest
 *       {@code TrackRef.state() == TrackState.COASTING} ({@code source() == DetectionSource.TRACKER})</td></tr>
 *   <tr><td>{@link #LOST}</td><td>{@code lockedTrackId} fell to {@code 0} after having been
 *       non-zero for this lock generation</td></tr>
 *   <tr><td>{@link #RELEASED}</td><td>the operator's own release patch was applied</td></tr>
 * </table>
 *
 * <p>Pure marker, no behavior — the state machine that computes which value applies lives in
 * {@code FollowTracker}, not here (same convention as {@code DetectionSource}/{@code TrackState}).
 */
public enum FollowState {
    REQUESTING,
    HOLDING,
    COASTING,
    LOST,
    RELEASED
}
