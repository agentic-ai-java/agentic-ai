# Agent Chat UI

Agent Chat UI provides a visualized way for developers to chat with any ARGI developed Agents.

## Quick Experience

> Go to the [examples](../examples) directory to experience real world usage.

1. Start backend agent

Go to the `src/test/java` directory, start the backend agent by running `StudioApplication`.
The unified application supports both **Graph** (e.g. `simple_workflow`) and **Agent** (e.g. `single_agent`, `research_agent`) APIs.

2. Then, start the chat ui

```shell
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

3. Chat with agent

Visit `http://localhost:3000`.

### Embedded mode

The ui can work in a embedded mode with any of your Spring Boot applications.

Just add the following dependency to your agent project:

```xml
<dependency>
	<groupId>io.github.agentic-ai</groupId>
	<artifactId>argi-studio</artifactId>
	<version>2.1.0-dev</version>
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

Build the static UI before packaging the Maven project:

```shell
cd agent-chat-ui
pnpm install --frozen-lockfile
pnpm run build:static
cd ..
../mvnw -pl :argi-studio -am -DskipTests package
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

By default, the UI connects to your backend Agent at `http://localhost:8080`, you can change the address at `.env.development` file.

```properties
# .env.development
NEXT_PUBLIC_API_URL=http://localhost:8080
# The agent to call in the backend application, backend application should register agent as required, check examples for how to configure.
NEXT_PUBLIC_APP_NAME=research_agent
NEXT_PUBLIC_USER_ID=user-001
```
