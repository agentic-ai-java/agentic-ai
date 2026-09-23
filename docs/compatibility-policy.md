# Compatibility Policy

Agentic Spring AI 2.x preserves the current public contract while new
enterprise runtime capabilities are designed and released. Compatibility is a
merge requirement, not a release-only check.

## Protected Surfaces

The following surfaces are protected throughout the 2.x line:

- Public and protected Java classes, packages, constructors, methods, nested
  types, enum constants, and documented exception contracts.
- Maven artifact coordinates and the Core/Extensions ownership boundary. Core
  must remain buildable and usable without an Extensions dependency.
- Existing `spring.ai.alibaba.*` configuration keys, activation rules, value
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
standalone legacy source fixture against candidate Core artifacts.

Extensions compatibility is verified from an isolated Maven repository. The
Core candidate is tested and installed into that isolated repository first;
then the pinned Extensions checkout is tested against those candidate artifacts.
This proves Extensions still compile and pass tests against the reviewed Core
candidate without adding any Extensions dependency to Core.
