package com.drones.vision.api.security;

import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The one real {@link AssetAuthority} (docs/plans/active/AUTH-ROLES-PLAN.md §3.9, wave B4): a
 * capability, paired with {@link VisibilityScope#includes(AssetId, Ownership)}, narrowed once more
 * for {@link #mayFly} by the caller's own assignment seat.
 *
 * <p>Reads the asset's {@link Ownership} via {@link AssetService#details(AssetId)} — the
 * <strong>unscoped</strong> overload, deliberately: {@link VisibilityScope#includes(AssetId,
 * Ownership)} is one of this class's own conjuncts, so the scope check must be performed here, not
 * pre-filtered away by asking a scoped read first. An unknown {@code asset} propagates {@link
 * java.util.NoSuchElementException} exactly as it already would once the caller's own downstream
 * application-service call resolves the same asset — same 404, same convention, no new mapping
 * needed.
 *
 * <h2>The seat narrowing on {@code mayFly} (IC-2)</h2>
 * A {@link VisibilityScope.Kind#ASSIGNED_ASSETS} caller (a PILOT, seat-wise, or a user with no
 * membership at all) may fly only an asset where {@link AssignmentRepositoryPort#roleFor} answers
 * {@link AssignmentRole#PILOT} — a {@code CREW} seat sees the asset (it is still in {@code
 * assignedAssets}) but may not command its flight. A MANAGER/ADMIN's {@link
 * VisibilityScope.Kind#GROUPS}/{@link VisibilityScope.Kind#UNBOUNDED} scope carries no assignment
 * row at all and is untouched by this narrowing — the third conjunct is a no-op for them.
 *
 * <h2>Two entry points, one rule</h2>
 * {@link #mayFly(AssetId)} (the {@link AssetAuthority} contract) reads the ambient {@link
 * CurrentUser}; {@link #mayFly(Authority, UserId, AssetId)} evaluates the identical rule against an
 * explicit actor/authority for the one caller that cannot reach {@link CurrentUser} —
 * {@code ManualControlWebSocketHandler}, whose {@code engage} frame is dispatched on a thread with
 * no {@code SecurityContext} (wave B4, docs/plans/active/AUTH-ROLES-PLAN.md §3.7). Not part of
 * {@link AssetAuthority} itself — the interface stays frozen at exactly three methods per §3.9 —
 * this is a second public method on the one concrete implementation, same status
 * {@link #ownershipOf(AssetId)} would have if it needed to be reused outside this class.
 */
@Component
public class CapabilityAssetAuthority implements AssetAuthority {

    private final CurrentUser currentUser;
    private final AssetService assetService;
    private final AssignmentRepositoryPort assignmentRepository;

    public CapabilityAssetAuthority(CurrentUser currentUser, AssetService assetService,
                                     AssignmentRepositoryPort assignmentRepository) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
    }

    @Override
    public boolean mayFly(AssetId asset) {
        return mayFly(currentUser.authority(), currentUser.userId(), asset);
    }

    /**
     * The {@link #mayFly(AssetId)} rule, evaluated against an explicit {@code authority}/{@code
     * actor} rather than the ambient {@link CurrentUser} — the escape hatch {@code
     * ManualControlWebSocketHandler} uses. {@link CurrentUser} resolves through Spring Security's
     * {@code SecurityContextHolder}, which is thread-local to the original HTTP request; a
     * WebSocket connection's later {@code engage} text frame is dispatched on the container's own
     * message thread, which carries no such context (see {@code ManualControlHandshakeInterceptor}'s
     * javadoc — this is exactly why it stashes {@code userId}/{@code scope}/{@code authority} into
     * the session's attribute map at handshake time, on the one thread where they resolve). This
     * overload lets that caller apply the identical rule to what it already captured, instead of a
     * second, independently-maintained copy of it.
     *
     * @param authority the caller's authority, resolved elsewhere (not from {@link CurrentUser})
     * @param actor     the caller's id, resolved elsewhere
     * @param asset     the asset to check
     * @return {@code true} iff {@code actor} may fly {@code asset}
     */
    public boolean mayFly(Authority authority, UserId actor, AssetId asset) {
        Ownership ownership = ownershipOf(asset);
        VisibilityScope scope = authority.scope();
        boolean seatOk = scope.kind() != VisibilityScope.Kind.ASSIGNED_ASSETS
                || assignmentRepository.roleFor(actor, asset)
                        .map(role -> role == AssignmentRole.PILOT)
                        .orElse(false);
        return authority.capabilities().contains(Capability.COMMAND_FLIGHT)
                && scope.includes(asset, ownership)
                && seatOk;
    }

    @Override
    public boolean mayOperateCamera(AssetId asset) {
        Ownership ownership = ownershipOf(asset);
        Authority authority = currentUser.authority();
        return authority.capabilities().contains(Capability.OPERATE_PAYLOAD)
                && authority.scope().includes(asset, ownership);
    }

    @Override
    public boolean mayForceSeat(AssetId asset) {
        Ownership ownership = ownershipOf(asset);
        return currentUser.authority().mayManageFleet(ownership);
    }

    private Ownership ownershipOf(AssetId asset) {
        return assetService.details(asset).summary().asset().ownership();
    }
}
