# DOMAIN-SEPARATION W1 — context walls: measured state and sub-waves

Companion to [DOMAIN-SEPARATION-PLAN.md](DOMAIN-SEPARATION-PLAN.md). That doc freezes the *target*
(8 contexts, 4 roles, urgency-classed communication). This one is the **context file for W1**: what
the code actually looks like today, measured rather than assumed, and the sub-waves that follow from it.

**Branch:** `feat/domain-separation` (parent) · one subbranch per sub-wave, merged back on green.

---

## 1. What the measurement changed

W1 in the parent plan was one wave: *"split vision-domain + vision-application into per-context module
pairs"*. Measuring the code first (scripts in the session scratchpad, method in §2) says that would have
failed on day one, and that the real work is smaller and differently shaped than expected.

| Assumption in the plan | Measured reality | Consequence |
|---|---|---|
| Split modules, then fix what breaks | **16 of 18 application packages are one strongly-connected component** — Maven cannot express a cycle | extraction is the *last* step, not the first |
| The coupling is large | **98 of 185 cross-package imports are javadoc-only** `{@link}` references | over half the apparent knot is documentation |
| Many contexts are entangled | on **real code edges**, only **one** context cycle survives: flight ↔ perception ↔ warehouse | five contexts are already extractable |
| The domain bag needs untangling | domain needs **2 type relocations** to become acyclic | the 93-record bag is cleaner than the services above it |
| ArchUnit must wait for the split | ArchUnit reads **bytecode** — javadoc-only imports are invisible to it | **walls can land first, before any file moves** |

That last row reorders the whole wave. An unused import still has to *resolve at compile time*, so
javadoc imports break the build the moment contexts become separate Maven modules — but they are
inert until then. So: enforce walls now, clean javadoc just before extraction.

---

## 2. Method (reproducible)

Three passes over `vision-application` and `vision-domain` sources, comments and string literals stripped:

1. **Package graph** — `import com.drones.vision.application.<pkg>.<Type>` occurrences → directed edges, Tarjan SCC.
2. **Real vs javadoc** — an import counts as *real* only if its simple name also appears in the body with
   imports/comments removed; otherwise it exists purely so `{@link Type}` resolves.
3. **Domain graph** — `domain.model` is one flat package, so coupling is by direct type reference, not
   import: for each of the 93 models + 37 ports, which other domain types its source names.

Contexts are then applied as a package→context map (§3) and the graph is collapsed to context level;
intra-context cycles are irrelevant (same future module), only cross-context cycles block extraction.

---

## 3. Context assignment

### Application packages → contexts

| Context | Packages today |
|---|---|
| identity | `identity`, `scope` |
| warehouse | `asset`, `device`, `category`, `discovery`, `fleet`, `usage`, (`exception` — dissolved, see W1.2) |
| perception | `stream`, `pipeline` |
| flight | `flight`, `geofence` |
| map | `map`, `mark` |
| learning | `training` |
| events-replay | `replay` |
| simulation | `simulation` |

### Domain: shared kernel

The only types every context may import. Deliberately tiny — ids and pure value objects, no aggregates:

`AssetId · DeviceId · StreamId · UserId · GroupId · UsageId · CategoryId · GeoPosition · Ownership ·
LifecycleState · Capability · BoundingBox · BearingDistance · GeoProjection` — plus `StreamDescriptor`
after W1.2 (see below).

Everything else is owned by exactly one context; the full 130-type assignment lives in the analysis
script and is reproduced per context in each sub-wave's MODULE.md as it lands.

---

## 4. The real graph today

**Application layer, real code edges only** (javadoc excluded):

```
warehouse  → perception(11), identity(6)
flight     → identity(6), warehouse(4)
learning   → identity(12), events(3), perception(2)
map        → identity(3), perception(1)
perception → flight(1), warehouse(1)
simulation → warehouse(4), perception(1)
events     → (none)
```

**One cycle:** `warehouse → perception → {warehouse, flight} → warehouse`. Everything else is a DAG.

**Domain layer, cross-context references:**

```
events     → perception(4), map(1), flight(1)
perception → warehouse(4), flight(1), events(1)
flight     → warehouse(4)
learning   → perception(1)
```

**One cycle:** `perception ↔ events`.

---

## 5. The cycle-breaking list — 15 edges total

This is the entire burn-down. Each row is small, and each has a defensible answer independent of the
distribution goal.

| # | Edge | What it is | Fix | Why it is right anyway |
|---|---|---|---|---|
| C1 | perception → warehouse (1) | `VideoSourceRegistry` uses `UnsupportedProtocolException` | dissolve the `exception` package: `UnsupportedProtocolException` → perception, `ProbeFailedException` → warehouse | an exception belongs to the context that throws it; a two-class package that both sides reach into is not a layer |
| C2 | perception → flight (1) | `UsageTracker` uses `GeofenceMonitor` | perception stops calling geofence directly; the worker composes telemetry → geofence at wiring level | breach evaluation is not the video pipeline's job |
| C3 | **warehouse → perception (11)** | `AssetService`/`DeviceService`/`FleetSummaryService` call `StreamService`, `ActiveStream`, `UsageTracker`, `TrackingConfigPatch` to answer *"is this asset live?"* | invert: warehouse declares a small read port (`StreamStateView`) it owns; perception feeds it. Composition of "inventory + live state" moves to the gateway where it belongs | **the architectural finding of this wave** — a CRUD context must not depend on runtime state; this is precisely what makes warehouse un-replicable today |
| C4 | domain: perception → events (1) | `PipelineConfig` references `EventRuleConfig` | `EventRuleConfig` → perception | it is a per-stream pipeline setting that the event engine *reads*; it was filed by consumer, not by owner |
| C5 | domain: perception → warehouse (2 of 4) | `VideoSourcePort`/`FeedTransmitterPort` reference `StreamDescriptor` | `StreamDescriptor` → shared kernel | a protocol+URI+options value object with no behavior, produced by warehouse and consumed by perception — textbook kernel |
| C6 | domain: ports taking whole `Device` (5) | `FlightCommandPort`, `ManualControlPort`, `TelemetrySourcePort`, `RecordingPort`, `StreamPublisherPort` | narrow to what they use (id + descriptor + capabilities) | ports should take what they need, not an aggregate from another context |

C1–C3 break the application cycle. C4–C5 break the domain cycle. C6 is hygiene that makes extraction
clean rather than being strictly required.

> **Landed in W1.2 — and it turned out cheaper than this table implies.** C1 and C2 were perception's
> *only* two outgoing cross-context edges, so removing them made perception a **sink**, and the whole
> application graph went acyclic on the spot. **C3 is therefore not an extraction blocker** — it is a
> design problem (a CRUD context reading runtime state is what stops warehouse from being replicated
> independently), and it can be paid on its own schedule, most naturally alongside the gateway
> composition in W2/W3. Two imports, not eleven, were standing between this codebase and separable
> modules.
>
> C4/C5 are recorded as **assignment decisions, not code changes**: `domain.model` is still one flat
> package, so nothing physically moves until W1.5 — there is no package boundary yet for a relocation
> to mean anything.

---

## 6. Sub-waves

Each is a subbranch off `feat/domain-separation`, each ends with its scoped build green and MODULE.md
updated, each is independently revertable.

| Wave | Scope | Risk | Exit criterion |
|---|---|---|---|
| **W1.1 — walls** | `ContextArchitectureTest` in vision-app: declare the 8 contexts, assert the measured edge set of §4 exactly, and freeze it. Known cycle recorded as explicit, named debt that must shrink | none — test only, no production file touched | test green; **any new cross-context edge fails CI** |
| **W1.2 — break the cycles** | C1, C2, C4, C5 (the small five) | low | application + domain context graphs acyclic except C3 |
| **W1.3 — warehouse ⇄ perception** | C3: `StreamStateView` port, composition moved to gateway | **medium — the design-heavy one** | warehouse has zero perception edges; fleet/asset/device responses unchanged on the wire |
| **W1.4 — javadoc de-import** | rewrite 98 cross-context `{@link X}` to fully-qualified form, drop the imports | mechanical, zero behavior | no cross-context import without a real code use |
| **W1.5 — package reorganization** | `domain.model.*` → `domain.<context>.model`, ports likewise; application packages regrouped under `application.<context>.*` | mechanical, huge blast radius (~390 files + every adapter/api import) | full build green, MODULE.md per context |
| **W1.6 — Maven extraction** | one module pair per context; role flags in vision-app | high, but trivial once W1.1–W1.5 hold | `./mvnw -B verify` green; `vision.roles` selects modules |

W1.1 and W1.2 are worth doing today. W1.3 deserves its own review. W1.5 must run alone on the branch —
nothing else can be in flight while every import in the repo moves.

---

## 7. What this wave deliberately does **not** do

- **No model divergence.** §9 of the parent plan (one drone as `Asset`/`Vehicle`/`Source`/`TrackedPosition`)
  is a *modeling* change per context, driven by need. W1 moves what exists; it does not fork records.
- **No broker, no roles, no network hops.** W2/W3 own those. Every call in W1 stays in-process.
- **No behavior change.** Wire contracts, DTOs and SSE payloads are byte-identical at the end of W1.
  The `verify` suite plus the existing REST/SSE tests are the proof.

---

## 8. Status

- [x] measurement + context assignment (this doc)
- [x] **W1.1 walls** — `ContextArchitectureTest`, 13 declared edges frozen
- [x] **W1.2 small cycles** — C1 (`exception` package dissolved) + C2 (`UsageTracker` telemetry-observer seam).
      **The application context graph is now acyclic**; mutual pairs held at zero by test.
      Verified: `./mvnw -B -pl vision-application,vision-api,vision-app test` green (vision-app 222/222)
- [ ] W1.3 warehouse → perception (C3) — *demoted from blocker to design debt; may move to W2 with the gateway*
- [x] **W1.4 javadoc de-import** — 70 cross-context javadoc-only imports removed across 43 files, each
      surviving `{@link}`/`@throws` reference rewritten fully-qualified. Delegated to three Sonnet
      agents on disjoint directory scopes (warehouse+simulation · learning+events+map ·
      flight+identity+perception); verified centrally, not from their reports: re-measurement reports
      **0 remaining**, exactly 43 files touched, no dangling FQNs, and
      `./mvnw -B -pl vision-domain,vision-application,vision-api,vision-app test` green
      (518 + 819 + 551 + 222 = **2110 tests**).
      The 98 measured earlier included intra-context imports; 70 is the cross-context subset that
      actually breaks Maven extraction. `{@code X}` mentions were correctly left bare — they resolve
      nothing and need no import.
- [x] **W1.5a domain package reorganization** — 130 types into `com.drones.vision.kernel` (15) +
      `com.drones.vision.<ctx>.domain.{model,port}`. Packages only; files stay in the `vision-domain`
      Maven module so W1.6 is a pure directory→module move. 197 files moved, references rewritten in
      573, 101 imports added where a same-package reference became cross-package. C4/C5 applied.
      `ContextArchitectureTest` grew domain coverage (4 rules now, incl. kernel isolation);
      ArchUnit's three domain/application rules gained `..kernel..`.
      Verified: domain 518 · application 819 · api 551 · app 222 · adapters 366 — **identical counts
      to before the move**.
- [ ] W1.5b application package reorganization → `com.drones.vision.<ctx>.application.*`
- [ ] W1.6 Maven extraction

### Package scheme (fixed in W1.5a, applies to W1.5b and W1.6)

```
com.drones.vision.kernel                       shared kernel — ids + pure value objects
com.drones.vision.<context>.domain.model       records/enums owned by the context
com.drones.vision.<context>.domain.port        that context's driven ports   (".out" dropped)
com.drones.vision.<context>.application.<f>    services, keeping today's feature subpackage,
                                               collapsed when the feature name equals the context
```

**The context is the outermost segment on purpose**: the context is the future Maven module, so
extraction stays a directory move. A layer-first layout (`domain.warehouse`) could not be extracted
without splitting a package tree in half.

### Known collision ahead of W1.5/W1.6

The branch `feat/visual-geo` adds two more adapters (`adapter-geo-grpc`, `adapter-tiles`) and a
`vision-model-contracts` module — present in this working tree as untracked leftovers, and in that
branch's `adapters/pom.xml`, but not on `master`. W1.5 rewrites every import in the repo, so that
branch must be merged **before** W1.5 or it will conflict with essentially every file it touches.
