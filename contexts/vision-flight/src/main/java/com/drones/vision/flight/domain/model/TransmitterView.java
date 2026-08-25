package com.drones.vision.flight.domain.model;

/**
 * How the operator's own transmitter is arranged — which hand holds which stick, and which way its
 * vertical axes read (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15).
 *
 * <h2>Why this is stored, and why it is not part of {@link ControlProfile}</h2>
 * Neither field changes a single microsecond on the wire: what the vehicle does is decided entirely
 * by axis → function → channel, which is {@link ControlProfile}'s job. These two answer a different
 * question — "draw my radio the way it actually is" — and they are facts about the *hardware in the
 * operator's hands*, not about the layout. Keeping them on {@link OwnedControlProfile} means the
 * safety-critical record stays exactly what it was, while the answer still follows the operator from
 * one machine to the next instead of living in one browser's local storage, where setting up on a
 * laptop and flying from the ground-station box meant answering twice.
 *
 * <h2>Neither is discoverable</h2>
 * A gamepad reports a number; nothing in it says whether the stick that produced it is under the
 * left thumb, or whether pushing forward makes that number rise. Both have to be told, once.
 *
 * @param stickMode   the transmitter's mode, 1–4. Mode 2 (throttle on the left) is the most common
 *                    outside Asia and is the default; modes 3 and 4 mirror 2 and 1
 * @param forwardIsUp whether pushing a stick forward makes its axis read <em>positive</em>. A
 *                    gamepad reports Y negative-up, while a transmitter channel rises with the
 *                    stick, and which one a given radio does is a property of that radio
 */
public record TransmitterView(int stickMode, boolean forwardIsUp) {

    /** The lowest and highest transmitter mode there is; there is no mode 0 and no mode 5. */
    public static final int MIN_STICK_MODE = 1;
    public static final int MAX_STICK_MODE = 4;

    /**
     * Mode 2, reading forward as positive — the arrangement most operators outside Asia fly, and
     * what every layout starts on until its owner says otherwise.
     */
    public static final TransmitterView DEFAULT = new TransmitterView(2, true);

    public TransmitterView {
        if (stickMode < MIN_STICK_MODE || stickMode > MAX_STICK_MODE) {
            throw new IllegalArgumentException("TransmitterView stickMode must be within ["
                    + MIN_STICK_MODE + "," + MAX_STICK_MODE + "]: " + stickMode);
        }
    }
}
