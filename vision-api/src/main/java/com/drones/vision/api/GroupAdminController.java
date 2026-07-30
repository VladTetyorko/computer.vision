package com.drones.vision.api;

import com.drones.vision.api.dto.CreateGroupRequest;
import com.drones.vision.api.dto.GroupResponse;
import com.drones.vision.application.GroupService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for group management (docs/U-SCOPE-PLAN.md, U-e slice 2) — list groups and
 * create one (optionally under a parent), the org-chart half of the org-settings surface.
 *
 * <p>Constructor-injected with {@link GroupService} only — the wave-1 {@code create}/{@code list}
 * it delegates to. Full tree management (rename, re-parent, delete-with-children, a dedicated
 * {@code tree()} read) was deferred by wave 1 and is not built here.
 *
 * <h2>Deliberate gaps (documented, not faked)</h2>
 * Same posture as {@link UserAdminController}: no role gate (role isn't on {@link CurrentUser}) and
 * no &le;-own-scope enforcement on {@code create}/{@code list} (both unscoped in wave 1's
 * {@code GroupService}; adding scoping needs application-layer work outside this wave's file scope).
 * With auth off (default) the dev admin can do all of this, so the default-off build is unchanged.
 */
@RestController
public class GroupAdminController {

    private final GroupService groupService;

    public GroupAdminController(GroupService groupService) {
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
    }

    /**
     * Lists all groups, sorted by name (the service's own ordering).
     *
     * @return every group
     */
    @GetMapping("/api/groups")
    public List<GroupResponse> list() {
        return groupService.list().stream().map(GroupResponse::from).toList();
    }

    /**
     * Creates a group, optionally under a parent.
     *
     * @param request the new group's shape
     * @return the created group
     */
    @PostMapping("/api/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@RequestBody CreateGroupRequest request) {
        return GroupResponse.from(groupService.create(request.toSpec()));
    }
}
