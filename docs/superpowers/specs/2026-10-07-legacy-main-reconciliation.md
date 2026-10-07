# Legacy Main Reconciliation

## Approved Outcome

The user asked to reconcile previously unmerged local changes after the Redis
foundation, checkpoint CAS and execution leases were merged into main. Adopt
still-applicable fixes and compatibility gates, record equivalent and historical
work, verify the integrated result, and push main without a PR or force push.

Baseline Core: `57b699db96eb4568b525571f53b1a521d0774759`.
Matching Extensions: `ed07835729405e7c8b97772d5b478d63ac1f9760`.

## Runtime Adoption

Adopt all twelve commits in `codex/core-review-fixes` (`b4f7eb3bd`). They fix
streaming fallback, persisted model-call limits, context editing, mandatory/ranked
tool selection, namespace listing, Redis retention, literal map metadata and
Studio state deltas, and add observation wiring documentation/regression coverage.
Preserve current Redis read-failure and interrupt handling, current tool budgets,
and all checkpoint CAS/lease lifecycle behavior. Apply changes as a merge or
patches against current main, never wholesale replacement with old source files.
Serializer changes must preserve ordinary user keys without weakening framework
object round trips. Configured thread limits persist; unconfigured limits must
not introduce run-only counters into checkpoint state.
Tool selection retains the existing total maxTools budget. Mandatory tools
reserve its slots before ranked optional tools. Only mandatory tools alone may
exceed that budget; they must not grant an additional optional-tool allowance.

## Compatibility Adoption

Restore the missing Make and CI gates from `codex/argi-compatibility-baseline`,
adapted to current repository facts. Core compatibility starts at ARGI rename
commit `e3de87198da2509168a975c461885cc6c4c1e7c7`; do not exempt public ARGI types.
Compile the existing Java 17 source fixture and compare all five runtime jars.

Extensions CI validates two immutable commits: historical ARGI baseline
`ec023a24910a0a30c0e3e1c810e4c3ac2ef0dc40` and current leased saver candidate
`ed07835729405e7c8b97772d5b478d63ac1f9760`. Use the existing checkout SHA and
repository `agentic-ai-java/argi-extensions`, full Core history for binary checks,
and checkout path `.ci/argi-extensions` outside all Maven target directories.
The build job requires both API and Extensions compatibility jobs.

The Extensions verifier performs Core clean install before Extensions clean test
against one isolated Maven repository. Default storage is a task-owned temporary
directory. Optional `EXTENSIONS_COMPAT_MAVEN_REPO` selects a caller-owned isolated
repository that must not be deleted by cleanup. Reject checkouts within Core's
target directories before Core clean can erase them. Failure must propagate.
Validate actual command execution/order/failure with controlled CLI fixtures,
not only source-string assertions. No new production dependencies.

## Equivalent And Historical Work

The individual thread-limit/map-marker/store branches overlap the reviewed
runtime fixes; adopt each behavior once. The old schedule retry/terminal cleanup
and hard tool-budget patches are already represented on main. Model retry delay
and scheduling-independent graph tests, legacy configuration/security/storage/
graph/agent contracts from enterprise-runtime-phase-0 already exist after ARGI
renaming. Keep their current code and fixtures.

Preserve the old stateless-worker proposal as historical planned work with a
clear not-implemented header and provenance. Do not implement its worker/task/
memory design as part of reconciliation. Preserve the pre-rewrite backup and
refactor branch, existing worktrees, `.codex/`, and `agentic-spring-ai-studio/`.
Do not remove branches or combine old pre-ARGI histories to make ancestry clean.

## Acceptance

- Offline regressions fail on the pre-adoption runtime and pass after fixes.
- Existing legacy, versioned checkpoint and lease tests remain passing.
- Make compatibility targets execute real gates and propagate failures.
- Five binary jars and three Java 17 consumer fixtures pass genuine validation.
- Both pinned Extensions checkouts clean-test against the same reviewed Core.
- Lint, Java format/Checkstyle, licenses, whitespace and new-secret checks pass.
- Independent task and whole-branch reviews have no unresolved blocking findings.
- Source-by-source reconciliation evidence records adoption/equivalence/archive.
- Push normally to main only after fresh validation; preserve user files.
