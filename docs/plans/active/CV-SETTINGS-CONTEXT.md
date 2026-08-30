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
| W1 perception domain | domain-modeler | running | | |
| W4 learning domain+app | domain-modeler → application-service | running (domain half) | | |
| W2 | | pending W1 | | |
| W3 | | pending W1+W4 | | |
| W5 | | pending W2+W4 | | |
| W6 | | pending (contract frozen, can start any time) | | |
| W7 / W8 | | after W6 | | |

## Handoffs
(filled per wave: port signatures, deviations from the plan)
