# Legacy Main Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax.

**Goal:** Adopt valid old fixes and compatibility gates into current main once.

**Architecture:** Merge the approved runtime fix branch against current main,
then adapt the old compatibility wiring to the ARGI baseline and pinned old/new
Extensions consumers. Keep equivalent source unchanged and archive future design.

**Tech Stack:** Java 17, Maven, JUnit/Mockito, Bash, Make, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-10-07-legacy-main-reconciliation.md

## Global Constraints

- Java 17; no new production dependencies, API removal or default mode changes.
- Preserve current Redis read errors/interrupts, hard tool budgets and CAS/leases.
- No MQ, worker recovery, tool receipts, automatic data migration or exactly-once claim.
- Keep user files, original worktrees, backup/refactor branches and untracked dirs.
- Manual edits use apply_patch; use signed-off commits; reports remain ignored.
- Isolated worktree codex/reconcile-legacy-main; normal main push after review/tests.

## Task 1: Adopt Runtime Fixes With Regression Evidence

**Source:** codex/core-review-fixes, head b4f7eb3bd; current base 57b699db9.
**Ownership:** only the 26 files in `git diff --name-only 57b699db9...b4f7eb3bd`,
including observation README/test. No build/CI/POM or other source edits.

**Interfaces:** Existing model hook/interceptor, serializer, saver/store and
Studio interfaces; no new public API. Preserve new guard/scope contracts.

Adopt the exact source commits in order:

```text
66eb9c0ef  streaming fallback
3ccdf0a30  persisted thread limits
87f7fc4cf  context-editing arguments
33d48c87c  mandatory/ranked tool selection
5a8cdee0e  unconfigured thread-state isolation
42a329783  namespace prefix filtering
51c97c507  legacy Redis retention
03e7507db  business map markers
a6feafcd3 malformed map markers
dc4fc2929 Studio state deltas
88ab27822 GraphResponse metadata markers
b4f7eb3bd observation documentation/execution test
```

- [ ] Add only the regression test hunks from the source branch first. A generated
  Git patch is suitable for this mechanical integration; new manual changes use
  apply_patch. Do not replace full old production files.

```shell
git diff --binary 57b699db9...b4f7eb3bd -- \
  argi-agent-framework/src/test argi-graph-core/src/test argi-studio/src/test \
  spring-boot-starters/argi-starter-graph-observation/src/test
```

- [ ] Run focused pre-adoption tests and retain behavioral RED evidence. Compile
  errors alone are insufficient; adapt a fixture if it assumes a new private
  helper rather than the actual public behavior.

```shell
./mvnw -B -Dmaven.repo.local=/tmp/argi-runtime-compat-m2.9nrvx3 \
  -pl :argi-agent-framework,:argi-graph-core,:argi-studio,:argi-starter-graph-observation \
  -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ModelFallbackInterceptorStreamingTest,ModelCallLimitHookOfflineTest,ContextEditingToolInputsTest,ToolSelectionContractTest,StoreIntegrationTest,RedisSaverRetentionTest,JacksonStateCloneTest,JacksonContainerTypeRecognitionTest,GraphResponseSerializationRoundTripTest,StudioStateDeltaTest,GraphObservationAutoConfigurationTest test
```

- [ ] Revert only the temporary test patch that this task wrote, then integrate
  the source branch with a no-commit merge. If history merging introduces any
  unrelated change, use the exact scoped source patches instead and report it.

```shell
git merge --no-ff --no-commit codex/core-review-fixes
```

- [ ] Preserve mandatory tools even above maxTools; apply the cap to ranked
  nonmandatory selections using the remaining total-budget slots. Mandatory
  overflow alone is allowed, not maxTools optional tools plus mandatory tools.
  Preserve streaming primary response, literal map
  payloads, framework object reconstruction and keyed thread counters.
- [ ] Run focused GREEN, then full root tests once. Confirm RedisSaver retains
  history without restoring old timeout-to-empty or interrupt-losing reads.
  Confirm Studio deltas continue to use guarded public runtime entry points.
- [ ] Run whitespace/format/Checkstyle checks and sign off the scoped merge or
  patch commit. Write report with exact commands, RED/GREEN output and risks.

## Task 2: Restore Real Compatibility Wiring

**Source:** codex/argi-compatibility-baseline at ba2211084, plus safe-checkout
intent from e25f03ad7. Do not re-add ARGI-equivalent old tests or fixtures.

**Files:** tools/make/java.mk, tools/scripts/verify-core-binary-compatibility.sh,
new tools/scripts/verify-extensions-compatibility.sh,
new tools/scripts/verify-compatibility-wiring.sh and behavior tests under
tools/tests, optional focused tools/scripts/verify-compatibility-wiring.py,
.github/workflows/build-and-test.yml, docs/compatibility-policy.md.
Historical baseline spec/plan from f7cba9dd5/b1edc14b7 may be preserved with
provenance if still useful; do not overwrite current plans or fixtures.

**Interfaces:** CLI `verify-extensions-compatibility.sh EXTENSIONS_DIR`, optional
EXTENSIONS_COMPAT_MAVEN_REPO; default caller-owned Maven binary `MAVEN_CMD` or mvn
when Extensions has no executable wrapper. Failure exit codes propagate.
Make binary/source/wiring/combined compatibility targets run sequentially.

- [ ] Read existing scripts and official GitHub workflow syntax before edits.
  Current checkout pin11bd71901bbe5b1630ceea73d27597364c9af683 remains unchanged.
- [ ] Add behavioral CLI tests: real Make recipes invoke controlled verifier
  stand-ins in binary-then-source order and propagate failure; the Extensions
  wrapper invokes Core clean install before Extensions clean test using one
  isolated Maven repo, rejects missing/unsafe paths before invoking Maven,
  retains caller-owned repository and propagates Core/Extensions failures.
- [ ] Capture RED before restoring the skipped Make recipes/helpers. Test-created
  fixture files stay in temporary directories, not in production helpers.
- [ ] Restore Make recipes and an explicit sequential combined target, so
  `make -j8 compatibility-check` cannot race source installs with binary builds.

```make
binary-compatibility-check:
	tools/scripts/verify-core-binary-compatibility.sh
source-compatibility-check:
	tools/scripts/verify-core-source-compatibility.sh
compatibility-check:
	$(MAKE) compatibility-wiring-check
	$(MAKE) binary-compatibility-check
	$(MAKE) source-compatibility-check
```

- [ ] Set binary default to e3de87198da2509168a975c461885cc6c4c1e7c7 and remove
  pre-ARGI removed-type exclusions. Keep baseline worktree cleanup safe and
  clean candidate modules before building so old target jars cannot mask gaps.
- [ ] Add a fail-closed wiring validator and actual helper execution tests. It
  must detect skipped compatibility recipes, missing jobs/build dependency,
  wrong baseline/type exclusions and unsafe checkout location. Use structured
  YAML parsing through the existing yamllint/PyYAML development toolchain;
  a small Python helper is allowed behind the Bash CLI. Tests must prove
  observable validator/CLI behavior, including semantic YAML failures, not
  match identical source strings. API CI runs make tools before the checker.
- [ ] Restore API/Extensions jobs and build.needs. API checkout uses full history.
  Extensions job matrix pins the historical and current commits from the spec
  and checks them out at .ci/argi-extensions. Each job runs the actual verifier.
- [ ] Update compatibility policy to the actual baseline, matrix, sequential
  checks and default/override Maven repository ownership. No action upgrades.
- [ ] Run behavior tests, make dry-run/actual wiring check, actionlint/yamllint,
  Bash syntax and codespell/license/whitespace checks. Run actual five-module
  binary and three-file source gates; root can schedule them after task review.
- [ ] Sign off a scoped commit and report exact evidence plus any unrun dynamic
  gate explicitly; do not count static checks as full API compatibility.

## Task 3: Final Reconciliation Evidence And Main Publication

**Ownership:** root verification/doc handoff. No new runtime features.

- [ ] Preserve the stateless-worker spec from dd1349fe2/e96fc14c9 with a clear
  historical/not-implemented header. Add a reconciliation table covering every
  unmerged nonarchival branch/commit and its adopted/equivalent/archive status.
- [ ] Record phase0 test/helper equivalence with file references, schedule/tool
  budget equivalent patches, and backup/refactor/untracked preservation.
- [ ] Run fresh Core clean install and current Extensions clean test in the
  isolated Maven repo; record module totals/skips and all new tests execution.
- [ ] Run genuine API binary/source gates against the ARGI rename baseline;
  run the wrapper against both immutable Extensions refs with matched Core.
- [ ] Run lint/licenses/Java checks/whitespace and redacted HEAD secret scan;
  distinguish the unchanged Extensions TLS test fixture from new secrets.
- [ ] Independent whole-branch review, repair blockers, then verify any repairs.
- [ ] Fetch latest main, integrate without force/replaying unrelated work,
  normal-push main and confirm remote SHAs; sync primary local main safely.
- [ ] Keep original branches/worktrees/untracked dirs, archive task scratch
  recoverably and preserve verification logs. No PR or registry publication.
