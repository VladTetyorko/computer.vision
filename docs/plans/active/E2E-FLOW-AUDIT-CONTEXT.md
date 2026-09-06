# E2E-FLOW-AUDIT — context

**Started 2026-09-05**, on `master` @ `423a9e13` (immediately after the four-feature stack
`auth-roles` → `source-onboarding-2` → `track-follow` → `crew-control` merged, 47 commits).

## The ask (owner, verbatim)

> check the e2e flows of the features. From controller handling to drone, from camera adding to Ui
> and so so so on. As a result — i expect Domain list, their features and propositions how to do it
> better and easier. More attention provide to scalability and other features development. The
> usability of application and easy access to all features are our priority.

## What that means for the deliverable

Three things, in this order of weight:

1. **Domain list** — every bounded context and adapter group, what it owns, and what it does *not*.
2. **Their features** — what each domain can actually do today, walked as **end-to-end flows**
   (a flow crosses domains; a feature list that stops at a module boundary answers nothing about
   whether the product works).
3. **Propositions** — how to make it better and easier, weighted toward:
   - **usability / easy access to features** (the owner's stated top priority),
   - **scalability**,
   - **the cost of building the next feature** (does the seam help or fight the next developer?).

Not in scope: writing product code. This audit produces a document; anything it proposes becomes its
own plan and its own branch.

## Method

Per `CLAUDE.md` ("For taking the context use mainly documentation, not the code"), the grounding is:

- `ARCHITECTURE.md` — the intended design (§2 module layout, §3 domain concepts, §6 identity model).
- **26 `MODULE.md` files, 8 776 lines** — the per-module truth, kept current by the delegation rule.
  These carry the real seams, gotchas and "left incomplete" notes, which is exactly what an audit needs.
- `docs/plans/README.md` — the plan-status authority (what is built vs specced vs declined).
- `docs/main/MASTER-MATRIX.md` — the row-level capability backlog.

**Method caveat, learned the same day:** a doc's own status line is not evidence. The merge that
preceded this audit found five `MODULE.md` wave entries agreeing that a build gate was "pre-existing"
when measuring against `master` proved it was not. So: where this audit makes a *claim about whether
something works*, it must name the file or the wire that shows it — and where it cannot, it says so
rather than repeating a doc's own summary.

## Flows to walk

| # | Flow | Rough domain path |
|---|---|---|
| A | **Add a camera / vehicle → video on screen** | discovery adapters → warehouse → perception (StreamPipeline) → publish-hls/mediamtx → web |
| B | **Controller → drone** | web controller setup → RC/manual-control → mavlink-core → adapter-mavlink → vehicle |
| C | **Detection → tracking → follow → training** | perception → cv-grpc → cv-service → web overlay; learning for the capture→label→promote loop |
| D | **Who may do what** | identity (Authority, scope) → flight seats/crew → enforcement points in api/app |
| E | **Session → telemetry → map → replay** | flight (AssetUsage) → map → events → web replay/ops |

## Deliverable

`docs/plans/active/E2E-FLOW-AUDIT-2026-09-05.md` — domain list, per-flow walk, then proposals ranked
by (usability × reach) against effort, each naming the domain it lands in.
