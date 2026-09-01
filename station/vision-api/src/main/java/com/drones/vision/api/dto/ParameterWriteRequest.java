package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/assets/{id}/parameters} (docs/plans/active/FLEET-RADIO-PLAN.md
 * R5) — an explicit, operator-initiated Tier-A/B parameter write. This is the request shape {@code
 * RemediationOrchestrator} deliberately refuses to synthesise for {@code PARAM_WRITE}-shaped
 * remediation (D6 — an auto-remediation may never guess a target value or a consent the operator
 * never gave; a dedicated endpoint carrying both is a different, explicit act).
 *
 * @param name    the vehicle parameter to write, e.g. {@code "SYSID_THISMAV"}. The exact spelling on
 *                the wire may differ from what the vehicle actually answers to (ArduPilot 4.7
 *                renamed several of these) — see {@link com.drones.vision.api.controller.AssetParameterController}
 *                for how the target spelling is resolved.
 * @param value   the value to write
 * @param consent the operator's explicit acknowledgement that they intend this specific write. This
 *                is not decoration: a value other than {@code true} refuses the request outright,
 *                before the asset is even resolved (400) — see the controller's own javadoc for what
 *                this gates and why.
 */
public record ParameterWriteRequest(String name, Double value, Boolean consent) {

    /**
     * @return {@link #name()}, guaranteed non-blank
     * @throws IllegalArgumentException if {@link #name()} is null or blank (→ 400)
     */
    public String requireName() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }

    /**
     * @return {@link #value()}, guaranteed present
     * @throws IllegalArgumentException if {@link #value()} is {@code null} (→ 400) — a parameter
     *                                  write with no target value is a malformed request, not a
     *                                  write of {@code 0}
     */
    public double requireValue() {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return value;
    }

    /**
     * The one consent check this endpoint enforces for every write it dispatches, Tier A included —
     * stricter than {@code RemediationService#writeParameter}'s own {@code explicitConsent}
     * parameter, which only Tier B actually gates. This endpoint's entire reason to exist is
     * carrying an explicit operator request (see this record's own class doc), so "explicit" is
     * enforced uniformly here rather than left to vary by a tier the operator issuing the request
     * has no reason to already know.
     *
     * @return {@code true}
     * @throws IllegalArgumentException if {@link #consent()} is not exactly {@code true} (→ 400,
     *                                  before the asset is resolved or the vehicle port is touched)
     */
    public boolean requireConsent() {
        if (!Boolean.TRUE.equals(consent)) {
            throw new IllegalArgumentException(
                    "Writing a flight-controller parameter requires explicit operator consent (consent: true)");
        }
        return true;
    }
}
