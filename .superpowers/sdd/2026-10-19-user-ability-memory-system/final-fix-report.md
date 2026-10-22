# F1–F7 final fix report

Date: 2026-10-22. Base: `53b0d18`. Scope is the seven confirmed findings in `final-review-report.md`; no V1–V10 migration was changed.

| Finding | Disposition and focused evidence |
| --- | --- |
| F1 disabled queued work | Fixed Rabbit and Redis resume/text admission using an eager persisted owner read and `ACTIVE` check before any model call. Disabled or missing work is discarded/ACKed. Rabbit zero-model tests and Redis admission recheck tests were RED before the guard and GREEN after it. |
| F2 report completion race | Fixed with a short transactional pessimistic session-row lock and completion recheck. All status writers use the same session lock; model calls remain outside transactions. A losing synchronous caller rereads and returns the winning stored report. Lock-verification unit coverage was RED before the change and GREEN after. Integration tests now submit distinct concurrent reports, derive evidence, and verify a later different report cannot change report, evidence, or derivation status. The concurrent test passed after the fix; its scheduling is not itself a deterministic RED proof. |
| F3 temporary credential | Fixed with persisted `mustChangePassword` checks on HTTP and WebSocket admission. Only exact me/CSRF/change-password/logout routes remain available; blocked HTTP business requests use `AUTH_PASSWORD_CHANGE_REQUIRED` (12009). A real-session before/after password-change test and WS persisted-state test were RED then GREEN. The runbook's former backend-bypass statement was corrected. |
| F4 administrator account discovery | Added ADMIN-only persisted account search/pagination and a safe account DTO, including invite-registered users, with no password hash or business-content fields. The UI loads/searches/pages persisted accounts and can act on rows. API integration and frontend request tests were RED before implementation and GREEN after. |
| F5 invitation query/revoke | Added safe paginated metadata, atomic `ACTIVE`→`REVOKED` conditional update, and administrator UI list/revoke actions. Plaintext remains issue-once; neither plaintext nor hash is returned by list. Focused service/API tests were RED before endpoints and GREEN after, including revoke-versus-redeem concurrent exactly-one-outcome coverage. |
| F6 resume grounding | Replaced exact presentation-label matching with deterministic matching of actual Skill category keys/labels and explicit safe technology aliases. Actual Python/Django and React-only presets retain resume slots without memory; unrelated text and partial words do not invent facts. Preset tests were RED before the helper and GREEN after. |
| F7 evidence difficulty | Resolve answer `slotId` against the effective persisted owner/session plan's `targetDifficulty`; missing plan/slot retains legacy session-difficulty fallback. Junior/senior/legacy tests were RED before mapping and GREEN after. Previously immutable evidence is unchanged. |

## Verification

- `./gradlew :app:compileJava --no-daemon`: PASS.
- Focused backend regressions for F1–F7: PASS after fixes. First full suite reached 595 tests, 49 skipped, with two obsolete test-fixture expectations; those were updated and the two affected classes passed on focused rerun.
- Final full backend test with `-I .superpowers/sdd/2026-10-19-user-ability-memory-system/task-9-test-heap.gradle`: PASS, `BUILD SUCCESSFUL in 3m 14s` after the two fixture corrections.
- `cd frontend && pnpm test`: PASS, 28/28.
- `cd frontend && pnpm run build`: PASS. Existing CSS minifier, Browserslist age, and large-chunk warnings remain.
- `git diff --check`: PASS (only Windows LF/CRLF conversion notices).

## Commits and remaining concerns

Code commits: `3243891` (F1/F2/F6/F7) and `655571e` (F3/F4/F5). No new migration or external service contract was introduced. The concurrent tests exercise H2 transaction ordering, not a deployed PostgreSQL/broker run; existing deployment verification remains relevant. Duplicate pre-completion model work is still possible by design, but cannot overwrite completed report/evidence.
