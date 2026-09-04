package com.drones.vision.api.controller;

import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.dto.AssignAssetRequest;
import com.drones.vision.api.dto.AssignmentResponse;
import com.drones.vision.api.dto.PilotResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.identity.domain.model.Assignment;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.identity.application.AssignmentService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for the pilot&rarr;asset assignment roster (docs/plans/done/U-SCOPE-PLAN.md, U-e slice
 * 2, feature 2): a manager grants/revokes "who flies what" within their own scope, and a pilot
 * reads their own assignments.
 *
 * <p>Constructor-injected with {@link AssignmentService} (the scoped grant/revoke + the pilot's own
 * roster read), {@link AssignmentRepositoryPort} (used read-only for {@code assignmentsForAsset}/
 * {@code roleFor} — the same precedent {@link AssetController} sets for reading a driven port
 * directly when no service method exposes exactly the read a controller needs), {@link AssetService}
 * (to 404 a pilots-list request for an asset outside the caller's scope), and {@link CurrentUser}
 * (the granter's authority, id, and the pilot's own id — {@code assign}/{@code unassign} pass {@code
 * currentUser.authority()}, docs/plans/active/AUTH-ROLES-PLAN.md wave B6). Four collaborators, under
 * the ceiling.
 *
 * <p><strong>Seat role</strong> (docs/plans/active/AUTH-ROLES-PLAN.md D6, wave B3): {@code PUT
 * .../pilots/{userId}} takes an optional {@link AssignAssetRequest} body — an absent body or absent
 * {@code role} field defaults to {@link AssignmentRole#PILOT}, so every pre-existing caller that
 * posts no body keeps granting a {@code PILOT} seat exactly as before this wave. The granting/
 * revoking user is now attributed as the actor for audit purposes, matching {@link
 * UserAdminController}'s actor-attribution fix in the same wave.
 *
 * <h2>Status codes</h2>
 * {@code PUT}/{@code DELETE .../pilots/{userId}} → {@code 204}; {@code 404}
 * ({@link java.util.NoSuchElementException}) for an unknown asset; {@code 403}
 * ({@link com.drones.vision.platform.AccessDeniedException}, mapped by {@link ApiExceptionHandler})
 * when the asset exists but is outside the granter's scope — a manager may not hand out an asset
 * they cannot themselves see (the &le;-own-scope rule). {@code 400} for a malformed asset/user UUID.
 * With auth off the granter's scope is unbounded, so every grant is permitted, exactly as before
 * scoping.
 */
@RestController
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final AssignmentRepositoryPort assignmentRepository;
    private final AssetService assetService;
    private final CurrentUser currentUser;

    public AssignmentController(AssignmentService assignmentService,
                                AssignmentRepositoryPort assignmentRepository,
                                AssetService assetService, CurrentUser currentUser) {
        this.assignmentService = Objects.requireNonNull(assignmentService, "assignmentService must not be null");
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Assigns a pilot to an asset, idempotently, in the given seat role.
     *
     * @param assetId the asset, as a canonical UUID string
     * @param userId  the pilot, as a canonical UUID string
     * @param request the seat role; absent body or absent {@code role} field defaults to
     *                {@link AssignmentRole#PILOT}
     */
    @PutMapping("/api/assets/{assetId}/pilots/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable String assetId, @PathVariable String userId,
                       @RequestBody(required = false) AssignAssetRequest request) {
        AssignmentRole role = request == null ? AssignmentRole.PILOT : request.toRole();
        assignmentService.assign(UserId.of(userId), AssetId.of(assetId), role, currentUser.userId(),
                currentUser.authority());
    }

    /**
     * Unassigns a pilot from an asset, idempotently.
     *
     * @param assetId the asset, as a canonical UUID string
     * @param userId  the pilot, as a canonical UUID string
     */
    @DeleteMapping("/api/assets/{assetId}/pilots/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable String assetId, @PathVariable String userId) {
        assignmentService.unassign(UserId.of(userId), AssetId.of(assetId), currentUser.userId(),
                currentUser.authority());
    }

    /**
     * Lists the pilots assigned to an asset, with each one's seat role. 404s if the asset is outside
     * the caller's scope, so existence is not revealed — the same rule the scoped asset read follows.
     *
     * @param assetId the asset, as a canonical UUID string
     * @return the assigned pilots
     */
    @GetMapping("/api/assets/{assetId}/pilots")
    public List<PilotResponse> pilots(@PathVariable String assetId) {
        AssetId id = AssetId.of(assetId);
        assetService.details(currentUser.scope(), id); // 404s an unknown or out-of-scope asset
        return assignmentRepository.assignmentsForAsset(id).stream()
                .map(a -> PilotResponse.from(a.pilot(), a.role()))
                .toList();
    }

    /**
     * The acting user's own assignments — the assets they may fly, with their seat role on each.
     *
     * @return the caller's assigned assets
     */
    @OpenByDesign(reason = "Self-scoped: takes no user parameter and filters by currentUser.userId().")
    @GetMapping("/api/me/assignments")
    public List<AssignmentResponse> myAssignments() {
        UserId self = currentUser.userId();
        return assignmentService.assignmentsFor(self).stream()
                .map(assetId -> AssignmentResponse.from(assetId,
                        assignmentRepository.roleFor(self, assetId).orElse(AssignmentRole.PILOT)))
                .toList();
    }
}
