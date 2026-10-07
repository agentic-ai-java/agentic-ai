# Compatibility Policy

ARGI 2.x preserves the current public contract while new
enterprise runtime capabilities are designed and released. Compatibility is a
merge requirement, not a release-only check.

## ARGI Baseline

The rename from Agentic Spring AI to ARGI is the migration boundary. ARGI 2.x
compatibility is measured from Core commit
`e3de87198da2509168a975c461885cc6c4c1e7c7`. Earlier Maven coordinates,
packages, and configuration prefixes require migration and are not part of this
baseline. The protected surfaces and deprecation rules below apply from this
ARGI baseline forward. No public ARGI types are exempted from the binary gate.

Core-to-Extensions compatibility is verified against two immutable Extensions
commits:

- Historical ARGI baseline: `ec023a24910a0a30c0e3e1c810e4c3ac2ef0dc40`
- Current leased saver candidate: `ed07835729405e7c8b97772d5b478d63ac1f9760`

## Compatibility Gate Contract

The wiring validator reads this contract and checks the Make, script and
workflow wiring against it:

```yaml
core_binary_baseline: e3de87198da2509168a975c461885cc6c4c1e7c7
extensions_repository: agentic-ai-java/argi-extensions
extensions_refs:
  - ec023a24910a0a30c0e3e1c810e4c3ac2ef0dc40
  - ed07835729405e7c8b97772d5b478d63ac1f9760
extensions_checkout_path: .ci/argi-extensions
extensions_maven_repo_override: EXTENSIONS_COMPAT_MAVEN_REPO
compatibility_gate_order:
  - compatibility-wiring-check
  - binary-compatibility-check
  - source-compatibility-check
```

## Protected Surfaces

The following surfaces are protected throughout the 2.x line:

- Public and protected Java classes, packages, constructors, methods, nested
  types, enum constants, and documented exception contracts.
- Maven artifact coordinates and the Core/Extensions ownership boundary. Core
  must remain buildable and usable without an Extensions dependency.
- Existing `argi.*` configuration keys, activation rules, value
  types, and default values.
- Checkpoint and Store formats, namespace rules, table names, key prefixes,
  serializer behavior, and thread lookup rules.
- Legacy runtime behavior for graph and agent invocation, streaming, state
  merge, interrupt and resume, checkpoint timing, execution order, Studio
  request and response shapes, and A2A request and response shapes.

Deprecated public APIs remain available for at least two minor releases.
Removal is allowed only in a major release.

## Runtime Semantics

Legacy execution remains the default behavior in 2.x. Upgrading the library must
not silently change recursion, retry, checkpoint, streaming, state merge, graph
execution, agent execution, model interceptor, or tool interceptor behavior.

Future enterprise runtime features must be additive and opt-in. Durable
superstep execution, typed runtime context, event stream revisions, checkpoint
capabilities, pending writes, and side-effect durability may be introduced by
new types, new overloads, default methods whose behavior is equivalent to the
old contract, independent capability interfaces, optional sidecar storage, and
explicit configuration.

Sidecar data must remain ignorable by old binaries. New storage may add sidecar
tables, collections, files, or key prefixes, but existing checkpoint rows, JSON,
thread keys, and Store data must remain readable by old runtimes during the
supported rollback window.

## Required Local Gates

Before merging compatibility-sensitive work, run these commands from the Core
repository root:

```bash
make compatibility-check
./mvnw test
make lint
make licenses-check
```

Pull requests also run build, test, lint, license, secret, Java matrix, API
compatibility, and Extensions compatibility gates.

## Core And Extensions Verification

Core is verified first. `make compatibility-check` compares the public and
protected Core runtime APIs against the fixed baseline and compiles the
standalone ARGI source fixture against candidate Core artifacts. The fixture
retains its historical `legacy-api-consumer` directory name. The wiring check
runs first, then binary and source checks run sequentially even when Make is
invoked with parallel jobs.

Extensions compatibility is verified from an isolated Maven repository. The
Core candidate is tested and installed into that isolated repository first;
then the pinned Extensions checkout is tested against those candidate artifacts.
This proves Extensions still compile and pass tests against the reviewed Core
candidate without adding any Extensions dependency to Core.

To run the Extensions gate locally, check out the pinned Extensions commit in a
separate directory outside Maven `target/` directories and run:

```bash
tools/scripts/verify-extensions-compatibility.sh /absolute/path/to/argi-extensions
```

By default, the wrapper creates and deletes a task-owned temporary Maven
repository. Set `EXTENSIONS_COMPAT_MAVEN_REPO` to use a caller-owned isolated
repository; the wrapper creates that directory when needed and leaves it in
place for the caller to inspect or reuse. The wrapper runs Core `clean install`
first, then Extensions `clean test`, and returns non-zero on dependency,
compilation, test, or compatibility failures.
