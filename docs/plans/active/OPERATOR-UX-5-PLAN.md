# OPERATOR-UX-5 — history that tells the truth

Fifth cycle of the operator-UX series (after OPERATOR-UX-4). Live walkthrough 2026-08-29 of Wall, Activity, Audit, Replay, Assets, Reports, System status, Roster, Settings, Devices. Same rule: the UI never impersonates data it does not have — and this time the backend stops manufacturing it.

## 1. Findings

| # | Where | What the operator sees | Why it is wrong |
|---|---|---|---|
| U1 | `/replay` | 10+ rows chip `● Flying now` on sessions started 23–25 Aug on an offline rover; rows with `0` samples listed like flights | `GET /api/usages`: **28 of the last 52 usages have no `endedAt`** — a usage is opened when a stream/telemetry starts and only closed on a clean stop; a crash, a killed process or a lost link leaves it open forever. Web renders `durationSeconds === undefined` as "Flying now" |
| U2 | `/activity`, `/monitor/audit` | "Flight command 'ARM' for asset 40dd46d8-be99-451f-b8e2-c03a291aea33"; actor `00000000` | Backend summaries are written with raw ids (`DefaultRemediationService`, `DefaultVehicleProfileService`, …). The audit table already shows the target name in its own column, then repeats the UUID in prose |
| U3 | `/manage/system` | Banner `System is down. Checked 0s ago.` while mediamtx is OK, SSE degraded, MAVLink unknown, CV down | One optional subsystem (cv-service) down is reported as the whole station being down |
| U4 | every page load | Toast `KEEP-IN breach — Demo operating area` | The bell seeds silently then toasts "what arrives after" — but the historic breach arrives *after* the seed (SSE replay), for an asset offline for days |
| U5 | `/assets` | 17 rows in insertion order, State column says `Offline` for all | No last-seen, no triage order — the third list (after `/fly`, `/command`) that does not use `core/fleet/triage-logic.ts` |

## 2. Design

**U1 — usages close themselves.** Backend (vision-warehouse `UsageSessionService` + vision-perception `UsageTracker`, wired in vision-app): a usage with no telemetry/frame activity for `vision.usage.idle-close` (root `application.yaml`, default 10 min) is ended at its last-activity instant, by a runner following the `TrackProjectionRunner` template (this codebase never uses `@Scheduled`); the same sweep runs once at startup so a crashed station never restarts with ghosts. `endedAt` is set to the last sample time, not "now" — the record must not claim minutes it did not see. Web: while a usage is open but its last activity is older than the stale threshold, the chip is neutral `Open · last sample 4d ago`, never `Flying now`; a usage with `sampleCount === 0` reads `No samples`, is not a replay link, and sorts last.

**U2 — names in prose.** Web, pure `core/audit/summary-logic.ts#humanizeSummary(summary, names: ReadonlyMap<string,string>)`: any UUID in the text that maps to a known asset/user display name is replaced by the name; unknown ids are shortened to 8 chars with no other change. Activity and audit render the humanized text (`title` keeps the raw). Actor `00000000…` (the root/system principal) reads `Station`. No backend change — history is immutable; this is presentation.

**U3 — a station is down when the station is down.** `system-status-facade` verdict: `down` only when the backend itself or the live transport is unreachable; otherwise the worst subsystem names itself: `Degraded — CV inference down`, `Degraded — Live updates degraded`. `Unknown` subsystems (no MAVLink vehicle claimed) never lower the verdict.

**U4 — a toast is news.** Bell: never toast a breach (or event) whose asset is not currently streaming, and never one older than the bell's mount time — both checks in a pure `shouldToast(event, mountedAt, streaming)`.

**U5 — the assets list triages.** Default order = `triage-logic` order (streaming → attention → last seen desc, never-seen last, simulated after real); new `Last seen` column with `offlineLabel`; the state cell keeps its dot. No grouping headers in a sortable table — the order carries it; clicking a column header still sorts.

## 3. Waves (disjoint files)

| Wave | Agent | Files |
|---|---|---|
| **W1 idle-close** | application-service | `contexts/vision-warehouse/**/usage/**`, `contexts/vision-perception/**/pipeline/UsageTracker*`, `station/vision-app/**` (runner + wiring + `application.yaml` key), those MODULE.md |
| **W2 replay + system + bell** | web-ui | `features/replay/**`, `features/system-status/**`, `shared/ui/notification-bell*`, `core/system-status/**` if present |
| **W3 prose + assets list** | web-ui | new `core/audit/summary-logic.ts` (+spec), `features/activity/**`, `features/audit/**`, `features/assets/**` |

Every wave: scoped build green (`-pl` with `-am` for W1), tests, MODULE.md, own files only.

Status: W1 (`23d13895`), W2 (`aba10429`), W3 (`e4360ba1`) built; **merged to master 2026-08-29**. Facts learned: no vision-perception path ever closed a usage on source/link failure — the idle sweep is the only recovery; the toast replay was the seed-on-first-tick idiom racing the SSE backlog. Open: `UsageSummary` carries no last-activity time on the wire (web estimates from `startedAt`); `/assets` has no column sort to override.
