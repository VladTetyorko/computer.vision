# CV-SETTINGS — working context

Spec: [CV-SETTINGS-PLAN.md](CV-SETTINGS-PLAN.md). Branch `feat/cv-settings`, cut 2026-08-30 from `feat/warehouse-ux` head `09f1cbba` (needs V28 + the plan; merge after warehouse-ux).

## Decisions taken (user, 2026-08-30)
- No feature flag: V29 seeds built-in profiles and **zero bindings**; unbound asset ⇒ byte-identical `PipelineConfig.defaults()`.
- `POST /api/datasets/{id}/train` relaxes `canAdminister` → `canManageOrg` (security-gate change, accepted). Promotion stays `canAdminister`.
- Vision rail 3 → 4 entries (`/vision/profiles`); `/settings/detection` deleted + redirected.
- All §8 recommended defaults accepted.

## Wave ledger
| Wave | Agent | Status | Commit | Notes |
|---|---|---|---|---|
| W1 perception domain | domain-modeler | built, uncommitted | | `CvProfile`/`CvProfileId`/`BindingScope`/`CvProfileBinding`/`CvProfileRepositoryPort` + tests, `contexts/vision-perception` green (see Handoffs) |
| W4 learning domain+app | domain-modeler → application-service | running (domain half) | | |
| W2 | | pending W1 | | |
| W3 | | pending W1+W4 | | |
| W5 | | pending W2+W4 | | |
| W6 | | pending (contract frozen, can start any time) | | |
| W7 / W8 | | after W6 | | |

## Handoffs
(filled per wave: port signatures, deviations from the plan)

### W1 → W2/W3

Built in `contexts/vision-perception/src/main/java/com/drones/vision/perception/domain/{model,port}/`
(package `com.drones.vision.perception.domain.model` unless noted). All framework-free, compact-ctor
validated, `./mvnw -B -pl contexts/vision-perception test` green (605 tests total in the module, 34 new).
**Nothing wired yet** — `DefaultStreamService`/`StreamService` are untouched; this wave is domain-only.

**Types**
```java
public record CvProfileId(UUID value) {
    public static CvProfileId random();
    public static CvProfileId of(String value); // throws IllegalArgumentException
}

public enum BindingScope { ORGANIZATION, CATEGORY, ASSET }

public record CvProfile(
    CvProfileId id, String name, String description, boolean builtIn, GroupId groupId, // kernel GroupId
    ModelRef model, double confidenceThreshold, int inferenceFps,
    List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled,
    TrackingConfig tracking, EventRuleConfig eventRule, Instant createdAt, Instant updatedAt) {

    public PipelineConfig toPipelineConfig(PipelineConfig defaults);
}

public record CvProfileBinding(
    BindingScope scopeKind, String scopeId, CvProfileId profileId, Instant createdAt) {}
```

**Port** (`domain.port`):
```java
public interface CvProfileRepositoryPort {
    Optional<CvProfile> findById(CvProfileId id);
    List<CvProfile> findAll();
    List<CvProfile> findAllByGroup(GroupId groupId); // excludes built-ins (groupId==null)
    CvProfile save(CvProfile profile);               // upsert by id
    void delete(CvProfileId id);                      // idempotent

    Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId);
    List<CvProfileBinding> findAllBindings();
    CvProfileBinding saveBinding(CvProfileBinding binding); // upsert by (scopeKind, scopeId)
    void deleteBinding(BindingScope scopeKind, String scopeId); // idempotent

    int countBindingsFor(CvProfileId profileId); // the delete-profile 409-still-bound check
}
```
No implementation exists (that's W3). W2's `CvProfileService`/`CvProfileResolver`/`CvProfileCache` and
`DefaultStreamService.start`'s consultation of the resolver are the next things this port needs to
be useful for anything.

**Deviations from the plan text, both deliberate — flag if you disagree:**
1. **§6 row W1 says `List<String>` isn't mentioned for `CvProfile`'s label fields**, but the plan's own
   §5.1 JSON shows them as arrays. Built them as `List<String>` (order-preserving, matches the wire
   shape and an operator's typed/arranged order) rather than reusing `PipelineConfig`'s `Set<String>`.
   `toPipelineConfig` converts list→set at the fold (`Set.copyOf(labelFilter)`), so `PipelineConfig`'s
   own containment-only semantics are unaffected. If W2/W5 want `Set` instead for symmetry with
   `PipelineConfig`, that's a one-file change here — say so and I'll adjust before it's built on.
2. **`CvProfile.groupId` nullability is validated bidirectionally**: `builtIn==true` requires
   `groupId==null`, and `builtIn==false` requires `groupId!=null` — not just "nullable when built-in"
   but "null if and only if built-in". This is stricter than the plan's prose ("groupId nullable only
   if the plan's built-ins are global") but matches §3.4's "seeded... not editable, forkable": a fork
   produces a new non-built-in, group-owned profile, so no code path should ever want a built-in with
   an owner or an owned profile with no owner. If a future built-in needs a group scope, this record
   needs a deliberate change, not a validation relaxation (noted in perception's MODULE.md Gotchas).
3. **`CvProfileBinding.scopeId` stayed a plain `String`**, not a typed sealed scope — the plan offered
   both. Reason: `CategoryId` is a kebab slug, not a UUID, so a union type still needs a per-`BindingScope`
   format check at the point that resolves it against a real kernel id; that point is naturally the
   resolver (W2), which already imports `AssetId`/`CategoryId`/`GroupId`. This record deliberately
   imports none of them.
4. **`updatedAt` must not be before `createdAt`** — an invariant the plan didn't spell out for this
   record but that every other timestamp-pair invariant in this module (`DetectionEvent.lastSeen`≥
   `firstSeen`) follows. Flag if W3's persistence layer needs to relax this for some migration/backfill
   reason.
5. **No `priority`/tier field** — §3.4 confirms tiers (T0–T3) stay a pure client heuristic, not modeled
   here. Confirmed nothing in §5.1's JSON contract needs it either.

**Test count:** 34 new tests (`CvProfileIdTest` 6, `CvProfileBindingTest` 5, `CvProfileTest` 23),
module total 605 (all green).
