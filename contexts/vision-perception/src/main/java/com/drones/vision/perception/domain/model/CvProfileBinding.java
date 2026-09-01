package com.drones.vision.perception.domain.model;

import java.time.Instant;

/**
 * Attaches a {@link CvProfile} to one scope — an organization ({@code GroupId}), a camera kind
 * ({@code CategoryId}), or a single asset ({@code AssetId}) — for the resolver's asset &rarr;
 * category &rarr; organization &rarr; platform fold (docs/plans/active/CV-SETTINGS-PLAN.md §3.1).
 *
 * <p>{@code scopeId} is a plain string rather than a typed id (a sealed {@code AssetId}/{@code
 * CategoryId}/{@code GroupId} union) deliberately: it mirrors the wire contract exactly (`{
 * "scopeKind":"ASSET|CATEGORY|ORGANIZATION", "scopeId":"…" }`, docs/plans/active/CV-SETTINGS-PLAN.md §5.2), and
 * {@code CategoryId} is a kebab-case slug while {@code AssetId}/{@code GroupId} are UUIDs — a
 * union type would still need a per-{@link BindingScope} format check, which belongs to the
 * application layer that already imports all three kernel id types (this record does not import
 * any of them). Parsing/validating {@code scopeId} against the concrete id type for its {@code
 * scopeKind} is the resolver's job, not this record's.
 *
 * <p>At most one binding may exist per {@code (scopeKind, scopeId)} pair — enforced by storage
 * (the migration's composite primary key), not here; {@code
 * CvProfileRepositoryPort#saveBinding} is an upsert on that pair.
 *
 * @param scopeKind which kind of thing {@code scopeId} identifies
 * @param scopeId   the scope's id, as a string — a UUID for {@link BindingScope#ORGANIZATION}/
 *                  {@link BindingScope#ASSET}, a kebab-case slug for {@link BindingScope#CATEGORY};
 *                  must not be blank
 * @param profileId the profile bound to this scope
 * @param createdAt when this binding was made; never changes afterward — rebinding a scope to a
 *                  different profile is a new {@code save}, which storage treats as replacing the
 *                  row for the same {@code (scopeKind, scopeId)}, {@code createdAt} included
 */
public record CvProfileBinding(BindingScope scopeKind, String scopeId, CvProfileId profileId, Instant createdAt) {

    public CvProfileBinding {
        if (scopeKind == null) {
            throw new IllegalArgumentException("CvProfileBinding scopeKind must not be null");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("CvProfileBinding scopeId must not be blank");
        }
        if (profileId == null) {
            throw new IllegalArgumentException("CvProfileBinding profileId must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("CvProfileBinding createdAt must not be null");
        }
    }
}
