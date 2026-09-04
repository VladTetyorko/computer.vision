package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Asset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The one implementation of {@link CvProfileService}.
 *
 * <h2>Audit target type</h2>
 * {@link com.drones.vision.platform.AuditTargetType} has no dedicated constant for a CV profile —
 * every entry this class writes reuses {@link AuditTargetType#MODEL} (the closest existing CV
 * fit), the same "reuse the closest existing value, documented" precedent {@code
 * DefaultModelRegistryService} sets for its own "promoted"/"rolled back" actions having no
 * dedicated {@link AuditAction}. A future wave touching {@code vision-platform} could add a
 * dedicated {@code CV_PROFILE} constant; out of this wave's write scope (docs/plans/active/CV-SETTINGS-CONTEXT.md,
 * W2 &rarr; W5 handoff).
 *
 * <h2>Scope gate</h2>
 * See {@link CvProfileService}'s own javadoc for the full canManageOrg / 403 vs. 404 rules. Every
 * mutation attempt is audited here, success or refusal alike — denials as {@code
 * DENIED:out of scope}, a built-in edit/delete refusal as {@code REFUSED:built-in}, a still-bound
 * delete refusal as {@code REFUSED:still bound} — reusing {@code DefaultDatasetService}/{@code
 * DefaultModelRegistryService}'s own "audit every attempt" idiom.
 *
 * <h2>Threading</h2>
 * Holds no mutable state of its own — all shared state lives behind {@link #cache} and the injected
 * collaborators.
 */
public final class DefaultCvProfileService implements CvProfileService {

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_FORK = "FORK";
    private static final String ACTION_BIND = "BIND";
    private static final String ACTION_UNBIND = "UNBIND";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";
    private static final String REFUSED_BUILT_IN = "REFUSED:built-in";
    private static final String REFUSED_STILL_BOUND = "REFUSED:still bound";
    private static final String ATTR_PROFILE_ID = "cvProfileId";
    private static final String ATTR_ACTION = "action";
    private static final String ATTR_RESULT = "result";

    private final CvProfileCache cache;
    private final CvProfileResolver resolver;
    private final AssetService assetService;
    private final AuditTrailPort auditTrail;
    private final Supplier<Instant> clock;

    public DefaultCvProfileService(CvProfileCache cache, CvProfileResolver resolver, AssetService assetService,
                                    AuditTrailPort auditTrail) {
        this(cache, resolver, assetService, auditTrail, Instant::now);
    }

    /**
     * Test seam: same as the 4-argument constructor, with an injectable clock so {@link
     * CvProfile#createdAt()}/{@link CvProfile#updatedAt()} are deterministic in tests instead of
     * depending on wall-clock time.
     */
    DefaultCvProfileService(CvProfileCache cache, CvProfileResolver resolver, AssetService assetService,
                             AuditTrailPort auditTrail, Supplier<Instant> clock) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public List<CvProfile> list(UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        return cache.snapshot().profiles().stream()
                .filter(profile -> profile.builtIn() || scope.includesGroup(profile.groupId()))
                .toList();
    }

    @Override
    public CvProfile get(CvProfileId id, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        CvProfile profile = require(id);
        if (!profile.builtIn() && !scope.includesGroup(profile.groupId())) {
            throw new NoSuchElementException("Unknown CV profile: " + id.value());
        }
        return profile;
    }

    @Override
    public CvProfile create(CvProfileSpec spec, GroupId groupId, UserId actor, Authority scope) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        CvProfileId id = CvProfileId.random();
        if (!scope.mayManageOrg()) {
            auditDenied(actor, id.value().toString(), ACTION_CREATE,
                    "Denied creating CV profile '" + spec.name() + "': out of scope");
            throw new AccessDeniedException("Not permitted to create CV profiles");
        }
        Instant now = clock.get();
        CvProfile profile = new CvProfile(id, spec.name(), spec.description(), false, groupId, spec.model(),
                spec.confidenceThreshold(), spec.inferenceFps(), spec.labelFilter(), spec.labelDenyFilter(),
                spec.detectionEnabled(), spec.tracking(), spec.eventRule(), now, now);
        CvProfile saved = cache.save(profile);
        audit(actor, id.value().toString(), AuditAction.CREATED, ACTION_CREATE, "CREATED",
                "Created CV profile '" + saved.name() + "'");
        return saved;
    }

    @Override
    public CvProfile update(CvProfileId id, CvProfileSpec spec, UserId actor, Authority scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        CvProfile existing = require(id);
        String targetId = id.value().toString();
        if (!scope.mayManageOrg()) {
            auditDenied(actor, targetId, ACTION_UPDATE, "Denied updating CV profile " + id.value() + ": out of scope");
            throw new AccessDeniedException("Not permitted to update CV profiles");
        }
        if (existing.builtIn()) {
            audit(actor, targetId, AuditAction.UPDATED, ACTION_UPDATE, REFUSED_BUILT_IN,
                    "Refused editing built-in CV profile '" + existing.name() + "'");
            throw new IllegalStateException("Built-in CV profile cannot be edited: " + id.value());
        }
        CvProfile updated = new CvProfile(id, spec.name(), spec.description(), false, existing.groupId(),
                spec.model(), spec.confidenceThreshold(), spec.inferenceFps(), spec.labelFilter(),
                spec.labelDenyFilter(), spec.detectionEnabled(), spec.tracking(), spec.eventRule(),
                existing.createdAt(), clock.get());
        CvProfile saved = cache.save(updated);
        audit(actor, targetId, AuditAction.UPDATED, ACTION_UPDATE, "UPDATED", "Updated CV profile '" + saved.name() + "'");
        return saved;
    }

    @Override
    public void delete(CvProfileId id, UserId actor, Authority scope) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        CvProfile existing = require(id);
        String targetId = id.value().toString();
        if (!scope.mayManageOrg()) {
            auditDenied(actor, targetId, ACTION_DELETE, "Denied deleting CV profile " + id.value() + ": out of scope");
            throw new AccessDeniedException("Not permitted to delete CV profiles");
        }
        if (existing.builtIn()) {
            audit(actor, targetId, AuditAction.UPDATED, ACTION_DELETE, REFUSED_BUILT_IN,
                    "Refused deleting built-in CV profile '" + existing.name() + "'");
            throw new IllegalStateException("Built-in CV profile cannot be deleted: " + id.value());
        }
        int bindings = cache.countBindingsFor(id);
        if (bindings > 0) {
            audit(actor, targetId, AuditAction.UPDATED, ACTION_DELETE, REFUSED_STILL_BOUND,
                    "Refused deleting CV profile '" + existing.name() + "': bound to " + bindings + " scope(s)");
            throw new IllegalStateException("CV profile is still bound to " + bindings + " scope(s): " + id.value());
        }
        cache.delete(id);
        audit(actor, targetId, AuditAction.DELETED, ACTION_DELETE, "DELETED", "Deleted CV profile '" + existing.name() + "'");
    }

    @Override
    public CvProfile fork(CvProfileId builtInId, String newName, GroupId groupId, UserId actor, Authority scope) {
        Objects.requireNonNull(builtInId, "builtInId must not be null");
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("newName must not be blank");
        }
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        CvProfile source = require(builtInId);
        if (!scope.mayManageOrg()) {
            auditDenied(actor, builtInId.value().toString(), ACTION_FORK,
                    "Denied forking CV profile " + builtInId.value() + ": out of scope");
            throw new AccessDeniedException("Not permitted to fork CV profiles");
        }
        if (!source.builtIn()) {
            throw new IllegalArgumentException("Only a built-in CV profile can be forked: " + builtInId.value());
        }
        Instant now = clock.get();
        CvProfileId newId = CvProfileId.random();
        CvProfile copy = new CvProfile(newId, newName, source.description(), false, groupId, source.model(),
                source.confidenceThreshold(), source.inferenceFps(), source.labelFilter(), source.labelDenyFilter(),
                source.detectionEnabled(), source.tracking(), source.eventRule(), now, now);
        CvProfile saved = cache.save(copy);
        audit(actor, newId.value().toString(), AuditAction.CREATED, ACTION_FORK, "CREATED",
                "Forked CV profile '" + saved.name() + "' from built-in '" + source.name() + "'");
        return saved;
    }

    @Override
    public CvProfileBinding bind(BindingScope scopeKind, String scopeId, CvProfileId profileId, UserId actor,
                                  Authority scope) {
        Objects.requireNonNull(scopeKind, "scopeKind must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        require(profileId);
        String targetId = profileId.value().toString();
        if (!scope.mayManageOrg()) {
            auditDenied(actor, targetId, ACTION_BIND,
                    "Denied binding CV profile " + profileId.value() + " to " + scopeKind + " " + scopeId
                            + ": out of scope");
            throw new AccessDeniedException("Not permitted to bind CV profiles");
        }
        validateScopeId(scopeKind, scopeId);
        CvProfileBinding binding = new CvProfileBinding(scopeKind, scopeId, profileId, clock.get());
        CvProfileBinding saved = cache.saveBinding(binding);
        audit(actor, targetId, AuditAction.UPDATED, ACTION_BIND, "UPDATED",
                "Bound CV profile '" + profileId.value() + "' to " + scopeKind + " " + scopeId);
        return saved;
    }

    @Override
    public void unbind(BindingScope scopeKind, String scopeId, UserId actor, Authority scope) {
        Objects.requireNonNull(scopeKind, "scopeKind must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        String targetId = scopeKind + ":" + scopeId;
        if (!scope.mayManageOrg()) {
            auditDenied(actor, targetId, ACTION_UNBIND, "Denied unbinding " + scopeKind + " " + scopeId + ": out of scope");
            throw new AccessDeniedException("Not permitted to unbind CV profiles");
        }
        validateScopeId(scopeKind, scopeId);
        cache.deleteBinding(scopeKind, scopeId);
        audit(actor, targetId, AuditAction.UPDATED, ACTION_UNBIND, "UPDATED", "Unbound " + scopeKind + " " + scopeId);
    }

    @Override
    public EffectiveProfile effective(AssetId assetId, PipelineConfig platformDefault, UserId actor,
                                       VisibilityScope scope) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(platformDefault, "platformDefault must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = assetService.details(scope, assetId).summary().asset();
        return resolver.resolve(asset.id(), asset.category(), asset.ownership().groupId(), platformDefault);
    }

    @Override
    public List<CoverageRow> coverage(PipelineConfig platformDefault, UserId actor, VisibilityScope scope) {
        Objects.requireNonNull(platformDefault, "platformDefault must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        return assetService.assets(scope, false).stream()
                .map(summary -> toCoverageRow(summary.asset(), platformDefault))
                .toList();
    }

    private CoverageRow toCoverageRow(Asset asset, PipelineConfig platformDefault) {
        EffectiveProfile effective =
                resolver.resolve(asset.id(), asset.category(), asset.ownership().groupId(), platformDefault);
        return new CoverageRow(asset.id(), asset.displayName(), asset.category(), effective.profileId(),
                effective.profileName(), effective.source(), effective.config().detectionEnabled(),
                effective.config().model(), List.copyOf(effective.config().labelFilter()),
                List.copyOf(effective.config().labelDenyFilter()));
    }

    private CvProfile require(CvProfileId id) {
        return cache.snapshot().findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown CV profile: " + id.value()));
    }

    /**
     * Validates {@code scopeId} against the concrete kernel id type {@code scopeKind} implies —
     * {@link AssetId}/{@link GroupId} (UUID) for {@link BindingScope#ASSET}/{@link
     * BindingScope#ORGANIZATION}, {@link CategoryId} (kebab-case slug) for {@link
     * BindingScope#CATEGORY} — reusing each type's own parsing/validation rather than duplicating a
     * format check here ({@link CvProfileBinding}'s own javadoc: this is deliberately the
     * application layer's job, not the domain record's). The parsed value is discarded; only its
     * validity matters.
     *
     * @throws IllegalArgumentException if {@code scopeId} is not a valid id for {@code scopeKind}
     */
    private static void validateScopeId(BindingScope scopeKind, String scopeId) {
        switch (scopeKind) {
            case ASSET -> AssetId.of(scopeId);
            case ORGANIZATION -> GroupId.of(scopeId);
            case CATEGORY -> new CategoryId(scopeId);
        }
    }

    private void auditDenied(UserId actor, String targetId, String action, String summary) {
        audit(actor, targetId, AuditAction.UPDATED, action, DENIED_OUT_OF_SCOPE, summary);
    }

    private void audit(UserId actor, String targetId, AuditAction auditAction, String action, String result,
                        String summary) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_PROFILE_ID, targetId);
        attributes.put(ATTR_ACTION, action);
        attributes.put(ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, auditAction, AuditTargetType.MODEL, targetId, summary, attributes));
    }
}
