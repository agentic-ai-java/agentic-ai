# AGENTS.md - AI Assistant Guide for Agentic AI

This file provides guidance for AI assistants working with the Agentic AI codebase.

## Project Overview

Agentic AI is a production-ready framework for building agents, workflows, and multi-agent applications. It is forked from Spring AI Alibaba and focuses on stateful agent runtime capabilities: graph orchestration, persistence, context engineering, and human-in-the-loop support. It also supports the multi-model integration capabilities provided by Spring AI.

> **Naming:** the product display name is *Agentic AI*. Published Maven coordinates (`io.github.agentic-spring-ai:*`), Java packages (`io.github.agentic.spring.ai`), configuration prefixes (`spring.ai.alibaba.*`), class names (`SpringAiAlibaba*`), the `Saa*` prefix, and metric names remain compatibility contracts. Do not rename them without a deprecation cycle.

**Key Features:**

- Multi-Agent Orchestration with built-in patterns
- Context Engineering with human-in-the-loop, context compaction, editing, model call limits
- Graph-based workflow with conditional routing, nested graphs, parallel execution
- A2A (Agent-to-Agent) client support (Nacos service discovery lives in the Extensions repository)
- Multi-model integration through Spring AI, plus MCP (Model Context Protocol)
- Embedded visual debugging studio

## Repository Structure

```
agentic-spring-ai/
├── agentic-ai-agent-framework/        # Multi-agent framework (Sequential, Parallel, Routing, etc.)
├── agentic-ai-graph-core/             # Runtime providing persistence, workflow orchestration, state mgmt
├── agentic-ai-studio/                 # Embedded UI for debugging agents visually
├── agentic-ai-bom/                    # Bill of Materials for dependency management
├── spring-boot-starters/              # Spring Boot Starters
│   ├── agentic-ai-starter-builtin-nodes/     # Built-in workflow nodes
│   └── agentic-ai-starter-graph-observation/ # Observability
├── tools/                             # Build and linting tools
└── docs/                              # Documentation
```

This repository holds the core only. Optional integrations live in separate repositories:

- [Extensions](https://github.com/agentic-spring-ai/agentic-spring-ai-extensions) - model and document contracts, A2A Nacos, config Nacos, AgentScope, JDBC/Redis/MongoDB graph persistence, the Docker code executor, and the tool-call sandbox.
- [Examples](https://github.com/agentic-spring-ai/examples/tree/main/examples) - chatbot, multi-agent, and graph engineering samples.

Since `2.1.0` the core no longer imports the Extensions BOM. Applications that use optional integrations must import both `agentic-spring-ai-bom` and the matching Extensions BOM.

## Build System

### Prerequisites

- **JDK**: 17 (Required by `java.version` property)
- **Maven**: 3.9.1+ (enforced by `requireMavenVersion` in the root pom; the wrapper ships 3.9.16)
- **Git**

### Common Build Commands

```shell
# Build the entire project (skip tests)
./mvnw -B package -DskipTests=true

# Build a specific module
./mvnw -pl :agentic-spring-ai-agent-framework -B package -DskipTests=true

# Clean project
./mvnw clean

# Run tests
./mvnw test

# Run linting checks (using Makefile)
make lint
make licenses-check
```

## Architecture & Key Concepts

### Core Components

- **Agent Framework**: Built-in agents like `SequentialAgent`, `ParallelAgent`, `RoutingAgent`, `LoopAgent`.
- **Graph Core**: Underlying engine for stateful agents, supporting persistence (PostgreSQL, MySQL, Oracle, MongoDB, Redis, File).
- **A2A (Agent-to-Agent)**: Enables agents to seek and communicate with each other using Nacos as a registry.
- **Studio**: Provides embedded visual tools for debugging agent workflows.

### Technology Stack

- **Framework**: Spring Boot 4.1.x, Spring AI 2.0.x
- **Model and Integration Layer**: Spring AI multi-model integrations, model starters, MCP, A2A, and Nacos
- **Observability**: Spring Cloud Observation (Micrometer/OpenTelemetry)

## Code Style & Conventions

### General Guidelines

- Follow **Spring AI Alibaba** standard code formatting.
- Use **Apache 2.0** license headers for all Java files.
- **Java 17** features are encouraged (records, switch expressions, text blocks).
- Avoid `System.out.println` - use SLF4J logging.
- Use `final` for local variables and parameters where appropriate.
- Use Lombok annotations (`@Data`, `@Slf4j`, etc.) to reduce boilerplate.

### Linting & Formatting

The project uses `make` for linting tasks:
- `make codespell`: Checks for spelling errors.
- `make yaml-lint`: Checks YAML file formatting.
- `make licenses-check`: Verifies license headers.

### License Header

```java
/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

## Testing

### Frameworks

- **JUnit 5** (`org.junit.jupiter`)
- **Mockito**

### Running Tests

```shell
# Run all tests
./mvnw test

# Run a specific test class
./mvnw -pl :<module-name> -Dtest=<TestClassName> test
```

## Tips for AI Assistants

1.  **JDK Version**: Project targets JDK 17. Use appropriate language features.
2.  **Spring Boot**: Uses Spring Boot 4.1.1 with Spring AI 2.0.1. The `jakarta.*` namespace applies throughout; there is no `javax.*` code.
3.  **Dependencies**: Check `agentic-spring-ai-bom` or parent pom for version management.
4.  **Makefile**: Use the Makefile in the root for project maintenance tasks (linting, license checks).
5.  **Structure**: When adding new features, prefer creating or updating modules within `agentic-ai-agent-framework` or `spring-boot-starters` depending on the scope.

## Important Links

- **Issues**: [https://github.com/agentic-spring-ai/agentic-spring-ai/issues](https://github.com/agentic-spring-ai/agentic-spring-ai/issues)
- **Source**: [https://github.com/agentic-spring-ai/agentic-spring-ai](https://github.com/agentic-spring-ai/agentic-spring-ai)
- **Contributing**: [CONTRIBUTING.md](CONTRIBUTING.md)
