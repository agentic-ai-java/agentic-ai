# Built-in Nodes

## Migrated Graph Nodes

The Core `KnowledgeRetrievalNode`, `HttpNode`, and `DocumentExtractorNode`
classes have been removed. Applications must use the Extensions-owned graph
node artifacts and packages.

For RAG retrieval nodes, declare:

```xml
<dependency>
  <groupId>io.github.agentic-spring-ai</groupId>
  <artifactId>agentic-spring-ai-graph-node-rag</artifactId>
</dependency>
```

Then import the Extensions package:

```java
import io.github.agentic.spring.ai.graph.node.rag.KnowledgeRetrievalNode;
```

For HTTP and document extraction nodes, declare:

```xml
<dependency>
  <groupId>io.github.agentic-spring-ai</groupId>
  <artifactId>agentic-spring-ai-graph-node-network</artifactId>
</dependency>
```

Then import the Extensions packages:

```java
import io.github.agentic.spring.ai.graph.node.network.DocumentExtractorNode;
import io.github.agentic.spring.ai.graph.node.network.HttpNode;
```

The Extensions artifacts are plain libraries. They do not add Spring Boot
auto-configuration, component scanning, or automatic graph node registration.

## Secure Defaults

The code and document nodes use restrictive defaults. Applications upgrading from earlier releases
must opt in to broader access explicitly.

### Code Execution

`DockerCodeExecutor` is provided by the Extensions-owned
`agentic-spring-ai-code-executor-docker` artifact in package
`io.github.agentic.spring.ai.graph.node.code.docker`. It disables networking,
uses a read-only root filesystem, drops capabilities, and applies CPU, memory,
swap, PID, and output limits.

```java
CodeExecutionConfig config = new CodeExecutionConfig()
    .setDisableNetwork(false) // Only when executed code requires outbound access.
    .setMemoryLimitBytes(512L * 1024L * 1024L)
    .setMemorySwapBytes(512L * 1024L * 1024L)
    .setPidsLimit(256)
    .setMaxOutputBytes(2L * 1024L * 1024L)
    .setTimeout(120);
```

Both Docker and local executors create an isolated child directory under `workDir`. Multiple code
blocks in one `executeCodeBlocks` invocation share that directory, and the directory is deleted when
the invocation finishes. Do not use `workDir` as cross-invocation storage. The local executor runs code
on the host and is only suitable for trusted input.

### Document Extraction

Local documents are restricted to the configured root. Remote documents allow only HTTP and HTTPS,
block private, loopback, link-local, and metadata addresses by default, cap response bytes, and enforce
connect, read, and total elapsed timeouts.

```java
DocumentExtractorNode node = DocumentExtractorNode.builder()
    .localRoot(Path.of("/srv/application-documents"))
    .maxBytes(10L * 1024L * 1024L)
    .totalTimeout(Duration.ofSeconds(60))
    .build();
```

For a trusted internal document service, private-network access can be enabled explicitly:

```java
DocumentExtractorNode node = DocumentExtractorNode.builder()
    .allowPrivateNetworkAccess(true)
    .build();
```

`HttpNode` applies URI and resolved-address preflight checks even with a custom `WebClient`. A custom
client connector must enforce the same policy during connect-time DNS resolution to prevent rebinding.

## AgentNode

`AgentNode` performs one blocking model call through a `ChatClient`, optionally with tools
attached, and writes the returned content to `outputKey` (default `agent_output`). It does
not implement an agent loop of its own; multi-step tool calling is Spring AI's tool-calling
loop inside `ChatClient.call()`.

At least one of `systemPrompt` and `userPrompt` is required. Both are rendered as
`PromptTemplate`s against the current state, so they may contain `{variable}` placeholders.

### Retries

```java
AgentNode node = AgentNode.builder()
    .chatClient(chatClient)
    .userPrompt("Summarise {document}")
    .maxRetries(2) // one attempt plus two retries, i.e. up to three model invocations
    .outputKey("summary")
    .build();
```

`maxRetries` is the retry count, not the total attempt count: `n` allows up to `n + 1` model
invocations. It defaults to `1`, so the default configuration calls the model twice before
giving up. The older `maxIterations` name is deprecated; it never controlled agent
iterations and has always been the same retry count.

### Model Call Error Handling

By default a model call that keeps failing is logged and the node writes an empty string to
`outputKey`, which downstream nodes cannot distinguish from an empty model response. To
propagate the failure instead, opt in:

```java
AgentNode node = AgentNode.builder()
    .chatClient(chatClient)
    .userPrompt("Summarise {document}")
    .throwOnModelError(true)
    .build();
```

`throwOnModelError` defaults to `false` for backward compatibility. When enabled, the
exception raised once the retries are exhausted is propagated with its cause chain intact.

### Strategy

`Strategy.REACT` (the default) and `Strategy.TOOL_CALLING` take the same code path at
execution time. They differ only in how tool callbacks are prepared at construction time:
`TOOL_CALLING` wraps every callback so that `ToolMetadata.returnDirect()` is `true`, which
returns the first tool result to the caller without a follow-up model call. `REACT` leaves
the callbacks untouched, so tool results are fed back to the model.
