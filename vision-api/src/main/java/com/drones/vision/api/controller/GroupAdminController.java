package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CreateGroupRequest;
import com.drones.vision.api.dto.GroupResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.identity.application.GroupService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Driving REST adapter for group management (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — list groups and
 * create one (optionally under a parent), the org-chart half of the org-settings surface.
 *
 * <p>Constructor-injected with {@link GroupService} and {@link CurrentUser}: both operations pass
 * {@code currentUser.scope()} into the service, which derives management authority from it and
 * enforces the ADMIN/MANAGER management gate plus the ≤-own-scope rules (only ADMIN may create a
 * root group; a manager may only create a child under a group they manage; {@code list} is
 * scope-filtered). A PILOT/empty scope is refused with
 * {@link com.drones.vision.platform.AccessDeniedException} (403 via {@link ApiExceptionHandler}).
 * With auth off (default) the dev principal's scope is unbounded, so the default-off build is
 * unchanged.
 */
@RestController
public class GroupAdminController {

    private final GroupService groupService;
    private final CurrentUser currentUser;

    public GroupAdminController(GroupService groupService, CurrentUser currentUser) {
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists the groups visible to the caller's scope, sorted by name (the service's own ordering).
     *
     * @return the visible groups
     */
    @GetMapping("/api/groups")
    public List<GroupResponse> list() {
        return groupService.list(currentUser.scope()).stream().map(GroupResponse::from).toList();
    }

    /**
     * Creates a group, optionally under a parent, within the caller's scope.
     *
     * @param request the new group's shape
     * @return the created group
     */
    @PostMapping("/api/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@RequestBody CreateGroupRequest request) {
        return GroupResponse.from(groupService.create(request.toSpec(), currentUser.scope()));
    }
}
