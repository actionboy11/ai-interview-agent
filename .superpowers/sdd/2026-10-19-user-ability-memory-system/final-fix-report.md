# F1–F7 final fix report

Date: 2026-10-22. Base: `53b0d18`. Scope is the seven confirmed findings in `final-review-report.md`; no V1–V10 migration was changed.

| Finding | Disposition and focused evidence |
| --- | --- |
| F1 disabled queued work | Fixed Rabbit and Redis resume/text admission using an eager persisted owner read and `ACTIVE` check before any model call. Disabled or missing work is discarded/ACKed. Rabbit zero-model tests and Redis admission recheck tests were RED before the guard and GREEN after it. |
| F2 report completion race | Fixed with a short transactional pessimistic session-row lock and completion recheck. Dead-letter replay now also checks/transitions through that lock without saving a stale entity; publication stays outside the transaction, and publish failure restores the dead letter to pending. The two-argument report path no longer preloads a session before acquiring the lock. A losing synchronous caller rereads and returns the winning stored report. Original concurrent report/derivation tests remain green; residual replay and preloaded-context regressions were RED before correction and GREEN after. |
| F3 temporary credential | Fixed with persisted `mustChangePassword` checks on HTTP and WebSocket admission. Only exact me/CSRF/change-password/logout routes remain available; blocked HTTP business requests use `AUTH_PASSWORD_CHANGE_REQUIRED` (12009). A real-session before/after password-change test and WS persisted-state test were RED then GREEN. The runbook's former backend-bypass statement was corrected. |
| F4 administrator account discovery | Added ADMIN-only persisted account search/pagination and a safe account DTO, including invite-registered users, with no password hash or business-content fields. The UI loads/searches/pages persisted accounts and can act on rows. API integration and frontend request tests were RED before implementation and GREEN after. |
| F5 invitation query/revoke | Added safe paginated metadata, atomic `ACTIVE`→`REVOKED` conditional update, and administrator UI list/revoke actions. Plaintext remains issue-once; neither plaintext nor hash is returned by list. Focused service/API tests were RED before endpoints and GREEN after, including revoke-versus-redeem concurrent exactly-one-outcome coverage. |
| F6 resume grounding | Replaced exact presentation-label matching with deterministic matching of actual Skill category keys/labels and explicit safe technology aliases. A Latin/digit identifier boundary now permits technology names adjacent to Han text, while excluding `CPython`, `ReactNative` and partial English words. Actual Python/Django and React-only presets retain resume slots without memory; unrelated text does not invent facts. Chinese-adjacency tests were RED before correction and GREEN after. |
| F7 evidence difficulty | Resolve answer `slotId` against the effective persisted owner/session plan's `targetDifficulty`; missing plan/slot retains legacy session-difficulty fallback. Junior/senior/legacy tests were RED before mapping and GREEN after. Previously immutable evidence is unchanged. |

## Verification

- `./gradlew :app:compileJava --no-daemon`: PASS.
- Focused backend regressions for F1–F7: PASS after fixes. First full suite reached 595 tests, 49 skipped, with two obsolete test-fixture expectations; those were updated and the two affected classes passed on focused rerun.
- Final full backend test with `-I .superpowers/sdd/2026-10-19-user-ability-memory-system/task-9-test-heap.gradle`: PASS, `BUILD SUCCESSFUL in 3m 14s` after the two fixture corrections.
- `cd frontend && pnpm test`: PASS, 28/28.
- `cd frontend && pnpm run build`: PASS. Existing CSS minifier, Browserslist age, and large-chunk warnings remain.
- `git diff --check`: PASS (only Windows LF/CRLF conversion notices).
- Residual correction verification: `:app:compileJava` and focused `:app:test` for `InterviewEvaluationDeadLetterServiceTest`, `InterviewEvaluationPersistenceIdempotencyTest`, `AbilityEvidenceDerivationServiceTest`, and `ResumeFactGroundingTest` PASS (`BUILD SUCCESSFUL in 30s`, 1 GiB init script). The prior full-suite/frontend results above predate this small backend-only correction; per scoped review request they were not repeated. The new replay-old-snapshot, two-argument preloaded-context, and Chinese-adjacency tests each failed against the preceding implementation before passing. Publish-failure semantics are covered by an additional green characterization test.

## Commits and remaining concerns

Code commits: `3243891` (F1/F2/F6/F7), `655571e` (F3/F4/F5), and `503707c` (F2/F6 residual correction). No new migration or external service contract was introduced. The concurrent tests exercise H2 transaction ordering, not a deployed PostgreSQL/broker run; existing deployment verification remains relevant. Duplicate pre-completion model work is still possible by design, but cannot overwrite completed report/evidence.
