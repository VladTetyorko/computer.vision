package com.drones.vision.api;

import com.drones.vision.api.dto.AssignmentResponse;
import com.drones.vision.api.dto.PilotResponse;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.AssignmentService;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the pilot&rarr;asset assignment roster (docs/U-SCOPE-PLAN.md, U-e slice
 * 2, feature 2): a manager grants/revokes "who flies what" within their own scope, and a pilot
 * reads their own assignments.
 *
 * <p>Constructor-injected with {@link AssignmentService} (the scoped grant/revoke + the pilot's own
 * roster read), {@link AssignmentRepositoryPort} (used read-only for {@code pilotsForAsset} — the
 * same precedent {@link AssetController} sets for reading a driven port directly when no service
 * method exposes exactly the read a controller needs), {@link AssetService} (to 404 a pilots-list
 * request for an asset outside the caller's scope), and {@link CurrentUser} (the granter's scope
 * and the pilot's own id). Four collaborators, under the ceiling.
 *
 * <h2>Status codes</h2>
 * {@code PUT}/{@code DELETE .../pilots/{userId}} → {@code 204}; {@code 404}
 * ({@link java.util.NoSuchElementException}) for an unknown asset; {@code 403}
 * ({@link com.drones.vision.application.AccessDeniedException}, mapped by {@link ApiExceptionHandler})
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
     * Assigns a pilot to an asset, idempotently.
     *
     * @param assetId the asset, as a canonical UUID string
     * @param userId  the pilot, as a canonical UUID string
     */
    @PutMapping("/api/assets/{assetId}/pilots/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable String assetId, @PathVariable String userId) {
        assignmentService.assign(UserId.of(userId), AssetId.of(assetId), currentUser.scope());
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
        assignmentService.unassign(UserId.of(userId), AssetId.of(assetId), currentUser.scope());
    }

    /**
     * Lists the pilots assigned to an asset. 404s if the asset is outside the caller's scope, so
     * existence is not revealed — the same rule the scoped asset read follows.
     *
     * @param assetId the asset, as a canonical UUID string
     * @return the assigned pilots
     */
    @GetMapping("/api/assets/{assetId}/pilots")
    public List<PilotResponse> pilots(@PathVariable String assetId) {
        AssetId id = AssetId.of(assetId);
        assetService.details(currentUser.scope(), id); // 404s an unknown or out-of-scope asset
        return assignmentRepository.pilotsForAsset(id).stream()
                .map(PilotResponse::from)
                .toList();
    }

    /**
     * The acting user's own assignments — the assets they may fly.
     *
     * @return the caller's assigned assets
     */
    @GetMapping("/api/me/assignments")
    public List<AssignmentResponse> myAssignments() {
        return assignmentService.assignmentsFor(currentUser.userId()).stream()
                .map(AssignmentResponse::from)
                .toList();
    }
}
