package com.drones.vision.platform;

import com.drones.vision.kernel.UserId;

import java.util.UUID;

/**
 * Fixed, well-known {@link UserId} standing in for "the platform itself" — the {@link
 * AuditEntry#actor()} an application service should pass when it makes a change on its own
 * initiative, with no user request behind it (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4,
 * Wave O11: a usage's PREFLIGHT/POSTFLIGHT phase transition triggers a flight-passport snapshot
 * capture that nobody asked for in the moment).
 *
 * <p>Distinct from {@code com.drones.vision.app.devsupport.DevPrincipal}'s dev-mode owning
 * principal, which stands in for "whichever user is operating the system right now" until the
 * identity phase lands (ARCHITECTURE.md §6) and every asset needs *someone* to own it. This
 * constant means the opposite: "no one asked for this — the platform did it, unattended." An audit
 * row naming this actor must read as exactly that, never be mistaken for a real person's action,
 * regardless of whether {@code vision.auth.enabled} is on or off.
 *
 * <p>Filed here rather than in {@code vision-app} because {@link AuditEntry}/{@link AuditTrailPort}
 * already live in {@code vision-platform}, and any bounded context's application layer may need to
 * audit an unattended action of its own — not only whatever {@code vision-app} wires up.
 */
public final class PlatformActor {

    /**
     * The fixed identity every unattended, platform-initiated change is audited under. Wraps {@code
     * UUID(0, 2)} — distinct from {@code DevPrincipal}'s {@code UUID(0, 0)}/{@code UUID(0, 1)} pair,
     * so the two well-known identities can never collide even though both are, today, effectively
     * hardcoded placeholders for the same missing identity phase.
     */
    public static final UserId USER_ID = new UserId(new UUID(0, 2));

    private PlatformActor() {
    }
}
