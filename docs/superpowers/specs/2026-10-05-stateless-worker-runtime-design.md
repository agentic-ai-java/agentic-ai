# Stateless Worker Runtime Proposal

> Historical proposal preserved during the 2026-10-07 branch reconciliation.
> This is a design snapshot from 2026-10-05, not an implemented Worker runtime.
> The Current Evidence section below describes that earlier revision. Since
> then, opt-in versioned Store/checkpoint contracts, Redis execution leases,
> and checkpoint fencing have shipped. Durable run recovery, tool receipts,
> scheduling recovery and MQ remain unimplemented by this proposal.

Status: Proposed, not implemented. Concrete storage and MQ drivers are not
selected. This proposal refines the earlier compatible enterprise-runtime
design; it does not replace the current executor.

## Goal And Compatibility

Allow any eligible Worker to recover a run after process loss and allow Worker
replicas to scale without owning authoritative session state. Preserve Java 17,
ARGI public APIs, Maven coordinates, configuration defaults, and existing
checkpoint formats.

Distributed execution is an explicit new path. Existing `invoke`, `stream`,
`schedule`, savers, tools, and interceptors remain available. Installing or
upgrading Core must not start a queue consumer or change retry semantics.
Adapters and migrations belong in Extensions; admission, autoscaling, identity,
deployment, and operational endpoints belong in the application/platform.
New records use expand-only sidecar storage; existing checkpoint rows, JSON,
tables, and key prefixes are not rewritten. Rollback stops new durable-run
admission. Outstanding durable runs require a compatible Worker to drain or
recover them; old binaries reading legacy state cannot resume those runs.

## Current Evidence

- `CheckpointExecutionQueue` serializes a namespace only inside one JVM and
  keys coordination by saver instance. It is not a distributed lock.
- `BaseCheckpointSaver` offers `list`, `get`, `put`, and `release`, not atomic
  ownership, fenced writes, run records, or a durable execution journal.
- `RunnableConfig.context()` is invocation-local, not a recovery journal.
- `ScheduledAgentTask` and `DefaultScheduledAgentManager` own local scheduler
  objects and active-task records. Fixing their retries and cleanup does not
  make scheduling durable.
- `ToolRetryInterceptor` retries locally. `AgentToolNode` timeouts cannot prove
  that a remote side effect was rolled back or never happened.
- Optional Redis/JDBC checkpoint adapters can externalize snapshots, but this
  alone does not provide distributed execution or durable tool receipts.
- Background `TaskTool` uses `DefaultTaskRepository` by default; its local task
  records/futures cannot be recovered by another Worker. In-memory Store/chat
  memory also remains local unless an external implementation is configured.

## Alternatives

| Approach | Benefit | Limitation |
| --- | --- | --- |
| Opt-in durable run layer (recommended) | Isolates new recovery semantics and preserves legacy behavior | Requires journal, fenced checkpoint commits, and adapters |
| Distributed lock around existing saver | Smaller change for cross-process serialization | Does not close side-effect, ACK, scheduling, or replay gaps |
| Replace executor with durable supersteps immediately | Supports partial parallel-work recovery | Too broad for the first deliverable; defer until contracts are tested |

The first implementation should assign a whole run to one Worker at a time.
Distributing individual graph nodes and parallel supersteps is a later phase.

## Ownership And Minimal Boundaries

Use three cohesive responsibilities instead of one interface per database table:

1. A durable run store owns run records, namespace ownership, checkpoint
   revisions, task journal/receipts, interrupts, and an event outbox. Its
   capability contract must state which writes are atomic.
2. A command transport delivers wake-up commands at least once. DB polling is
   a valid first transport; MQ is optional. It does not own run status.
3. A versioned definition resolver loads the exact graph, tool, schema, and
   codec versions recorded for a run.

These are design boundaries, not approved Java signatures. Reuse existing
capability interfaces where possible. Do not add no-op durability methods to
`BaseCheckpointSaver` or claim every existing saver supports durable execution.
Core adds no production storage or MQ dependency; production adapters remain
in Extensions/platform. In-memory implementations are test/reference surfaces,
not evidence of cross-process durability.

DB or durably configured Redis is the source of truth. Redis is not necessarily
a cache: if chosen as authoritative storage, persistence, failover loss bounds,
retention, and atomic-script/cluster-key constraints must be explicit. No
eviction or TTL may discard unfinished runs or unresolved tool receipts.
The first adapter should keep authoritative records in one transactional
backend. Mixing a DB run store with an independently written Redis checkpoint
is not an atomic commit and must not be advertised as the same capability.

Workers retain only bounded, disposable caches and in-flight execution state.
They do not persist Java closures, compiled graph objects, client connections,
or credentials. Durable state stores versioned data and references; credentials
are resolved at execution time through existing application infrastructure.
Validate the complete configured execution path, including long-term Store,
chat memory, background tasks, and callbacks. A background tool must create a
durable task plus wake-up record, not a local future that outlives the run.
Until that adapter exists, fail fast for background-task mode in durable runs.
In-memory-only authoritative memory is likewise unsupported in durable Worker
mode; disposable read-through caches are permitted. Legacy behavior is unchanged.

## Durable Identity

Persist at submission:

- trusted tenant scope, `runId`, client submission key and input hash;
- thread/checkpoint namespace and immutable graph/tool/schema/codec versions;
- execution mode, deadline, budget policy, status, and next durable position.

Persist before dispatching each side-effecting task:

- a stable logical task ID, node occurrence/iteration, namespace, tool version,
  canonical input hash, idempotency key, and planned arguments;
- attempts as separate records, never as part of the idempotency key;
- output/error references, external receipt, timestamps, and recovery status.

`toolCallId` alone is insufficient: models can regenerate IDs, loops can repeat
nodes, and different runs/tenants can use the same ID. Persist model output and
the task plan before invoking tools so recovery does not regenerate a different
task identity. Define canonical input encoding and hash/codec version. Reusing
an identity with a different input is a consistency error, not a new attempt.

Model calls have their own durable journal entries: logical call ID, canonical
request hash, provider/model/options and offered-tool-definition versions,
attempt status, completed response and tool-call payload, finish reason, usage,
and codec version. Dynamic tool references must resolve to the recorded
definitions, not invocation-local Java callbacks. Recovery reuses completed
model responses. A lost response may be requested again only under an explicit
model replay policy and budget accounting; the output can change and provider
charges can repeat. No tools dispatch until the response and task plan are
durable. Thus re-querying a lost model response never discards a plan whose
tools have already run. Exactly-once model invocation is not promised.

## Submission, Execution, And Recovery

1. Submission atomically creates a deduplicated run record and wake-up outbox
   entry. Repeated client keys with the same input return the same run; a
   different input is rejected.
2. A Worker consumes a command, loads durable state, and acquires a lease plus a
   monotonically increasing fencing token for the tenant/thread namespace.
   Exclusivity is across runs sharing that namespace, not merely per `runId`.
3. Resolve the recorded definition versions. Missing/incompatible versions
   fail without executing tools; never silently use the newest graph.
4. Execute from the committed boundary, journal nondeterministic results and
   planned tasks, and reuse completed receipts instead of invoking them again.
5. Every checkpoint commit validates owner, unexpired lease, fencing token,
   expected checkpoint revision, and expected run state. Atomically advance
   the run/checkpoint position and publishable outbox entries in the chosen
   authoritative backend. Receipt success may already have been committed
   separately and is referenced by the checkpoint.
6. ACK only after durable completion, durable interrupt, or a durable retry/
   reconciliation handoff with its wake-up record. ACK is never based only on
   Worker memory or an uncommitted event publication.
7. A recovery scanner re-enqueues expired leases and due retries from storage.
   Duplicate or reordered commands are harmless wake-ups: terminal/stale state
   is acknowledged without re-execution; busy claims do not start a second run.

Lease renewal uses backend-authoritative time. A Worker losing its lease stops
scheduling new work and requests cooperative cancellation. Fencing applies to
checkpoint, run, journal, and outbox writes; a lock alone cannot prevent stale
writes. A late remote side effect is still possible after lease loss, so tool
idempotency/reconciliation remains necessary.

Broker visibility timeouts are renewed during long work where required, but
broker consumer ownership never substitutes for namespace fencing. Outbox
publication is at least once; consumers deduplicate durable events using
`runId + sequence`. Live token streaming is best effort unless separately
journaled and must not be described as a resumable durable stream.

## Tool Reliability

Each tool declares whether it is read-only, externally idempotent, supports
status reconciliation, or is unsafe to retry after an ambiguous outcome.
Absent a declaration, side-effecting calls are treated conservatively.

The durable wrapper reserves the persisted task identity before dispatch. A
completed receipt returns the recorded result. In-flight/ambiguous receipts
are reconciled rather than blindly re-executed. Receipt and result retention
must cover the supported retry, queue-redelivery, and run-recovery window.

| Outcome | Durable behavior |
| --- | --- |
| Success and result persisted | Reuse receipt on retry/recovery |
| Known pre-dispatch/transient failure | Retry under bounded policy |
| Permanent validation/auth/business failure | Fail without automatic retry |
| Timeout/disconnect after possible dispatch | Reconcile or replay only with external idempotency; otherwise `IN_DOUBT` |
| Worker loss after external effect, before receipt | Same ambiguity rule; no generic exactly-once promise |

Use the same externally accepted idempotency key across retries and Worker
failover. A local result cache or receipt cannot close the crash window between
remote side effect and local result persistence. Exactly-once effects require
backend-specific transactional participation or an external deduplication
contract; the framework promises neither for arbitrary tools.

Apply connection/request/attempt timeouts beneath the run deadline. Retries
must be bounded by attempts, elapsed deadline, and cost budget, with exponential
backoff and jitter. Persist the next retry time and release the Worker rather
than block a reactive thread. Use one retry owner in durable mode; legacy tool
interceptors and transport retries must not multiply attempts invisibly.
Cancellation is cooperative, not proof that a side effect did not occur.

## HITL, Scheduling, And Scaling

Persist interrupts with deterministic IDs, payload schema/version, expiry,
resolution, and audit identity. Another Worker may resume only the same
recorded run/version after an atomic deduplicated resolution. Approval commands
must not rely on a local waiting thread.

Persist due schedules and their occurrence IDs in the platform/Extensions.
Schedulers atomically claim each occurrence and create a run plus outbox entry.
The existing local scheduling API is unchanged; merely running one local
`TaskScheduler` per replica would duplicate jobs.

Scale on backlog age, runnable backlog, lease occupancy, and execution latency,
with caps for model/provider quotas, storage capacity, and tenant concurrency.
Scale-in drains consumers, stops new claims, and finishes/checkpoints or hands
off work before lease expiry. It must not delete in-doubt tasks. Define separate
limits for Worker concurrency and graph-internal parallelism.

Propagate trace context in command headers and correlate logs/events with run,
task, attempt, and lease IDs. Do not put full prompts, credentials, or raw tool
payloads into default logs. Dead-letter/reconciliation flows retain actionable
failure references, not secrets.

## Delivery Order And Exit Gates

1. Lock the legacy compatibility baseline and neutral identity/commit/receipt
   contract tests. No driver or execution-path switch in this phase.
2. Implement the first production authoritative backend and DB-polling
   transport in Extensions/platform, using Core's neutral contracts. Require
   real two-process duplicate delivery, namespace claim, lease expiry, stale
   fenced checkpoint commit, run-state CAS, and side-effect-free recovery tests.
3. Add durable task planning, receipts, deadlines, retries, and reconciliation.
   Verify model-journal replay, receipt reuse, same idempotency keys on failover,
   and timeout ambiguity. Inject process loss before/after external dispatch,
   receipt, and checkpoint commits. Only this phase validates tool recovery.
4. Add the selected MQ adapter, transactional wake-up outbox, recovery scanner,
   and drain/scale-in lifecycle. Verify duplicates, ACK windows, and ordering.
5. Add durable schedules/HITL and deployment guidance. Extend to parallel
   pending-write/superstep recovery only as a separately reviewed change.

Every stage retains the existing `invoke/stream/schedule` test suite, source and
binary compatibility checks, historical checkpoint fixtures, and pinned
Extensions verification. New durable mode fails fast when a capability is
missing. Legacy threads never silently switch execution mode.

Required fault tests include duplicate submission/delivery, concurrent runs on
one namespace, stale Worker commit, lease expiry during an external call,
missing definition version, different input for the same task ID, process loss
after remote effect, retry exhaustion, durable interrupt resume on another
Worker, outbox re-delivery, and scale-in under backlog.

## Open Deployment Decisions

Choose the first authoritative backend and MQ from deployment requirements,
not from Core API convenience. Required inputs are acceptable recovery/data-loss
bounds, delivery throughput, existing operational infrastructure, and external
tool idempotency support. Until those choices are supplied, only the neutral
proposal is in scope; no Redis/Kafka/RabbitMQ dependency is added.
