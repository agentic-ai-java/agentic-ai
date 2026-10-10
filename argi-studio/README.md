# Agent Chat UI

Agent Chat UI provides a visualized way for developers to chat with any ARGI developed Agents.

## Quick Experience

> See the [Examples repository](https://github.com/agentic-ai-java/argi-examples/tree/main/examples) for real-world usage.

1. Start backend agent

Import the root `pom.xml` into your IDE and run [StudioApplication](src/test/java/io/github/agentic/ai/StudioApplication.java) from the test sources.
For a local demo without external MCP services, add `--spring.ai.mcp.client.enabled=false --argi.agent.studio.execution.auth-token=local-studio-token` to the program arguments.
The unified application supports both **Graph** (e.g. `simple_workflow`) and **Agent** (e.g. `single_agent`, `research_agent`) APIs.

2. Then, start the chat ui

```shell
cd argi-studio/agent-chat-ui # From the repository root.
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

3. Chat with agent

Visit `http://localhost:3000/?agent=single_agent`. Open Studio settings, set `Execution Token`
to the backend token (`local-studio-token` for the local demo), and reload the page.

### Embedded mode

The ui can work in a embedded mode with any of your Spring Boot applications.

Just add the following dependency to your agent project:

```xml
<dependency>
	<groupId>io.github.agentic-ai-java</groupId>
	<artifactId>argi-studio</artifactId>
	<version>2.1.0-RC1</version>
</dependency>
```

Run your agent, visit `http://localhost:{your-port}/chatui/index.html`, and now you can chat with your agent.

Studio keeps static UI assets public, but all Studio API endpoints fail closed until a backend token is configured:

```properties
argi.agent.studio.execution.auth-token=change-me
```

The embedded static UI does not read this value from build-time environment variables. Open the UI
configuration panel and set `Execution Token`; the browser stores it in `sessionStorage` for the
current tab session and sends it as `X-Agentic-Studio-Token` on discovery, execution, and thread requests.

Checkpoint keys created by older Studio versions used only `threadId`. Migrate known legacy threads
before accepting traffic after an upgrade; runtime reads intentionally do not fall back to unscoped
keys because that would permit cross-user access:

```java
saver.migrateLegacyThread(
    RunnableConfig.builder().threadId(threadId).build(),
    RunnableConfig.builder().threadId(threadId)
        .addMetadata(RunnableConfig.APP_NAME_METADATA_KEY, appName)
        .addMetadata(RunnableConfig.USER_ID_METADATA_KEY, userId)
        .build());
```

From the repository root, build the static UI before packaging the Maven project:

```shell
cd argi-studio/agent-chat-ui
pnpm install --frozen-lockfile
pnpm run build:static
cd ../..
./mvnw -pl :argi-studio -am -DskipTests package
```

### Standalone mode

First, clone the repository,

```bash
git clone https://github.com/agentic-ai-java/argi.git

cd argi/argi-studio/agent-chat-ui
```

Install dependencies:

```bash
pnpm install
# or
# npm install
```

Run the app:

```bash
pnpm dev
# or
# npm run dev
```

The app will be available at `http://localhost:3000`.

Start a backend Agent first and configure its execution token as described above.
By default, the UI connects to `http://localhost:8080`; change the address in `.env.development` if needed.
Open a known Agent or Graph with `?agent=<agent-name>` or `?graph=<graph-name>` to access Studio settings,
set the matching `Execution Token`, and reload the page. The home page cannot load its lists until a token is set.

```properties
# .env.development
NEXT_PUBLIC_API_URL=http://localhost:8080
# The agent to call in the backend application, backend application should register agent as required, check examples for how to configure.
NEXT_PUBLIC_APP_NAME=research_agent
NEXT_PUBLIC_USER_ID=user-001
```
