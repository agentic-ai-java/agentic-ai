# Legacy Branch Reconciliation

## Status

Runtime fixes and compatibility wiring are integrated and independently reviewed.
Real API and both pinned Extensions wrapper checks have passed. Final whole-
branch review and hygiene remain in progress. No publication is claimed by
this intermediate record.

Core starting main: `57b699db9`.
Extensions main: `ed078357` (no additional source work was missing there).
Integration branch: `codex/reconcile-legacy-main`.
Raw evidence: `/tmp/argi-legacy-integration.uhXHEb`.

## Source Reconciliation

| Source branch or commit | Decision | Evidence |
| --- | --- | --- |
| `codex/core-review-fixes` / `b4f7eb3bd` | Adopted all 12 commits by merge | `39ac5185c`; helper/API correction `dabdcd3da` |
| `codex/fix-thread-limit` | Covered once by reviewed runtime merge | `ModelCallLimitHookOfflineTest`; configured counter persists, unconfigured counter stays out of state |
| `codex/fix-state-map-markers` | Covered once by reviewed runtime merge | Jackson clone/container and GraphResponse metadata regressions |
| `codex/fix-store-contracts` | Covered once by reviewed runtime merge | Namespace prefix and legacy Redis retention regressions |
| `codex/fix-schedule-lifecycle` | Already equivalent on starting main | Retry and terminal one-shot cleanup patches match the published reliability fixes |
| `codex/runtime-reliability-fixes` | Runtime patches already equivalent; future proposal archived | Hard tool budget, retry and cleanup are on main; proposal is explicitly not implemented |
| `codex/argi-compatibility-baseline` | Adapted missing gates to current ARGI | `8380ada93`, safe-path correction `ce27abfc1`, setup-error propagation `3ec92f324` |
| `codex/enterprise-runtime-phase0-baseline` | Already equivalent after rename | RetryDelayCalculator and deterministic retry/parallel/error/serialization tests |
| `codex/enterprise-runtime-phase0-contracts` | Already equivalent after rename | Legacy agent/graph/serialization/configuration and Studio security fixtures |
| `codex/enterprise-runtime-phase-0` | Contracts already equivalent; missing wiring adopted separately | Current ARGI fixtures retained; checkout outside target directories required |
| `backup/main-before-origin-rewrite-20260826` | Preserved as historical backup | No old project naming/history reapplied |
| `refactor` | Preserved as historical branch | No whole-branch merge into current ARGI |

Duplicate branches need not become ancestors of main to prove behavioral
adoption. They are preserved, not deleted or force-reset to hide their history.
The historical worker design is kept at
`specs/2026-10-05-stateless-worker-runtime-design.md`, with a dated provenance
header distinguishing delivered Redis/CAS/leases from proposed recovery/receipts.

## Runtime Verification

Offline pre-adoption regressions reproduced actual defects:

- Graph Core: 29 tests, 6 failures and 5 errors.
- Agent: 22 tests, 13 failures.
- Studio: 8 tests, 4 failures.

Focused integrated regressions passed. A pre-review tool-selection correction
retained the old total budget: mandatory tools reserve slots; only mandatory
tools alone may exceed maxTools. Its RED showed 3 failures, GREEN all 6 passed.
Serializer extraction into package-private MapEnvelopeSupport preserved wire
fields and passed 96 targeted serializer, CAS, lease and selection tests.
Javap confirmed no new envelope symbols remain on JacksonDeserializer.

Fresh full Core build:

```shell
./mvnw -B -Dmaven.repo.local=/tmp/argi-runtime-compat-m2.9nrvx3 clean install
```

`final-core-clean-install.log`: BUILD SUCCESS, 1482 tests, zero failures/errors,
219 existing skips. No new skipped regression test is counted as coverage.
Candidate/installed Graph Core jar hashes match:

```text
3ce559b37e2ca910969cf47cb673878a5f598b2733ecc8b09f5cfe5203256fc7
```

Direct Extensions tests against that reviewed candidate:

| Immutable ref | Tests | Failures/errors | Existing skips |
| --- | ---: | ---: | ---: |
| `ec023a24910a0a30c0e3e1c810e4c3ac2ef0dc40` | 399 | 0 | 27 |
| `ed07835729405e7c8b97772d5b478d63ac1f9760` | 445 | 0 | 27 |

Current Extensions includes all 19 real Valkey lease tests with no skips. These
direct Maven runs are distinct from actual wrapper validation. Both historical
and current wrappers passed Core clean install followed by Extensions clean test.
They used the same absolute isolated repository sequentially. Each wrapper
re-ran all 1482 Core tests before the respective 399/445 Extensions tests.

## Compatibility Gates

The binary baseline is the ARGI rename commit
`e3de87198da2509168a975c461885cc6c4c1e7c7`, with no public-type exclusions.
Make wiring runs real binary/source gates sequentially. CI checks both pinned
Extensions refs outside Maven targets and requires API/Extensions jobs before
build. Workflow validation uses a standalone actionlint v1.7.12 and existing
PyYAML development tools, not a new application dependency.

CLI tests exercise Make invocation/order/failure, shared isolated Maven cache,
cache ownership, invalid checkout rejection and semantic YAML failures.
Initial wiring tests failed against skipped recipes; integrated tests passed.
The Extensions helper now canonicalizes relative cache paths, rejects checkouts
or caches inside relevant Maven targets, preserves caller-owned caches, and
propagates setup failures before Maven runs. Tests reproduce the original skipped
recipes, relative-cache bug and masked setup failure, then pass after repairs.

Actual `make compatibility-check` passed, executing wiring, binary and source
gates sequentially. All five module jars were compatible against the ARGI
rename baseline; all three consumer files compiled with Java 17. Raw evidence:
`final-actual-api-compatibility.log`. No Make placeholder was counted as a check.
Historical wrapper evidence: `final-wrapper-extensions-baseline.log`.
Current wrapper evidence: `final-wrapper-extensions-current.log`.

## Residual Risk

The observation fixture prints Micrometer AssertionError/onErrorDropped on both
the pre-adoption baseline and integrated code. Independent review confirmed it
is existing test/context-propagation debt, not resolved by this adoption. It is
not evidence that all observation paths are error-free.

No MQ, automatic Worker recovery, tool receipt protocol, exactly-once guarantee
or automatic storage migration is introduced. Original worktrees, branches and
the untracked `.codex/` and `agentic-spring-ai-studio/` directories are preserved.

## Pending

Final whole-branch review/hygiene and normal main push/local synchronization.
