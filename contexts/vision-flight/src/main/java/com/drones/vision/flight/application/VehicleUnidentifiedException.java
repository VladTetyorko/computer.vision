package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.UnidentifiedReason;

import java.util.Objects;

/**
 * Thrown by {@link ManualControlService#engage} when the resolved link's vehicle is {@link
 * com.drones.vision.flight.domain.model.VehicleKind#UNKNOWN} (docs/plans/active/FLEET-RADIO-PLAN.md
 * R2, decision D3: "UNKNOWN must refuse to engage, not hand out a guess. The operator picks a kind
 * explicitly, or does not fly").
 *
 * <p>Extends {@link IllegalStateException} rather than introducing a new throws clause: {@link
 * ManualControlService#engage} already documents {@code IllegalStateException} as one of its two
 * refusal outcomes, and {@code ManualControlWebSocketHandler}'s existing generic {@code
 * catch (IllegalStateException e)} would otherwise catch this too and message-sniff it the same
 * fragile way it must for the causes that have no dedicated exception type. This type exists so that
 * refusal does not have to be sniffed: {@link #reason()} carries the specific {@link
 * UnidentifiedReason} structurally, and the handler catches this subtype first to map it to its own
 * dedicated wire code instead.
 */
public final class VehicleUnidentifiedException extends IllegalStateException {

    private final UnidentifiedReason reason;

    public VehicleUnidentifiedException(UnidentifiedReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * @return which of the three facts refused this engage — never a guess (see {@link
     *         UnidentifiedReason})
     */
    public UnidentifiedReason reason() {
        return reason;
    }
}
