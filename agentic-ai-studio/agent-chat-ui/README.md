# Agent Chat UI

Agent Chat UI provides a visual way for developers to chat with Agentic AI agents.

## 🚀 How to use

### Embedded mode

The ui can work in a embedded mode with any of your Spring Boot applications.

Just add the following dependency to your agent project:

```xml
<dependency>
	<groupId>io.github.agentic-spring-ai</groupId>
	<artifactId>agentic-spring-ai-studio</artifactId>
	<version>2.1.0-dev</version>
</dependency>
```

Run your agent, visit `http:localhost:{your-port}/chatui/index.html`, and now you can chat with you agent.

### Standalone mode

First, clone the repository,

```bash
git clone https://github.com/agentic-spring-ai/agentic-spring-ai.git

cd agentic-spring-ai/agentic-ai-studio/agent-chat-ui
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

## Build and deploy

```shell
pnpm run deploy # This will build and copy static files to src/main/resources
```
