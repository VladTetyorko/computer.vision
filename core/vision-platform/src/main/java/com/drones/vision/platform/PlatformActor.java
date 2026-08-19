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
     * The fixed identity every unattended, platform-initiated change is audited under: {@code
     * ffffffff-ffff-ffff-ffff-ffffffffffff}.
     *
     * <p>Deliberately <em>outside</em> the {@code UUID(0, n)} namespace every other well-known id in
     * this repository lives in, rather than the next free slot in it. That namespace is not
     * reserved for constants — {@code db/seed/dev/V90001__dev_accounts.sql} seeds real user rows at
     * {@code UUID(0, 2)} (manager) and {@code UUID(0, 3)} (pilot), and {@code V12__map_layers.sql}
     * stamps the COP layer {@code UUID(0, 2)} in its own id space. A platform actor sitting anywhere
     * in that range would eventually be handed the same id as a real account, and the one thing this
     * constant exists to guarantee is that an unattended action is never attributed to a person.
     * Being far outside the range makes that structural rather than a matter of counting carefully.
     *
     * <p>All-ones is also unreachable by accident: {@link UUID#randomUUID()} stamps version 4 into
     * the id, so it can never generate this value for a real user.
     */
    public static final UserId USER_ID = new UserId(new UUID(-1L, -1L));

    private PlatformActor() {
    }
}
