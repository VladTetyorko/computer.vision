package com.drones.vision.flight.domain.model;

import java.time.Duration;

/**
 * The outcome of one Mechanism A request (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 4a) --
 * {@code MAV_CMD_SET_MESSAGE_INTERVAL}, a runtime request the aircraft is asked to honour for this
 * session only. Not a write, not persisted, gone on the next reboot.
 *
 * @param messageId the MAVLink message id requested
 * @param interval  the requested interval; {@code Duration.ZERO} means "disable"
 * @param outcome   how the aircraft responded
 * @param detail    an honest human sentence, e.g. why {@code outcome} is what it is; nullable
 */
public record MessageIntervalOutcome(int messageId, Duration interval, RemediationResultCode outcome, String detail) {

    public MessageIntervalOutcome {
        if (messageId < 0) {
            throw new IllegalArgumentException("messageId must not be negative: " + messageId);
        }
        if (interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("interval must not be null or negative: " + interval);
        }
        if (outcome == null) {
            throw new IllegalArgumentException("MessageIntervalOutcome outcome must not be null");
        }
    }
}
