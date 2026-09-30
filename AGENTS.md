# AGENTS.md - AI Assistant Guide for ARGI

This file provides guidance for AI assistants working with the ARGI codebase.

## Project Overview

ARGI (Agent Runtime and Graph Intelligence, pronounced "AR-jee") is a production-ready framework for building agents, workflows, and multi-agent applications. It is forked from Spring AI Alibaba and focuses on stateful agent runtime capabilities: graph orchestration, persistence, context engineering, and human-in-the-loop support. It also supports the multi-model integration capabilities provided by Spring AI.

> **Naming:** the product display name is *ARGI*. Published Maven coordinates use `io.github.agentic-ai:argi-*`, Java packages use `io.github.agentic.ai`, configuration prefixes use `argi.*`, and framework-specific public class names use the `Argi*` prefix.

**Key Features:**

- Multi-Agent Orchestration with built-in patterns
- Context Engineering with human-in-the-loop, context compaction, editing, model call limits
- Graph-based workflow with conditional routing, nested graphs, parallel execution
- A2A (Agent-to-Agent) client support (Nacos service discovery lives in the Extensions repository)
- Multi-model integration through Spring AI, plus MCP (Model Context Protocol)
- Embedded visual debugging studio

## Repository Structure

```
argi/
├── argi-agent-framework/        # Multi-agent framework (Sequential, Parallel, Routing, etc.)
├── argi-graph-core/             # Runtime providing persistence, workflow orchestration, state mgmt
├── argi-studio/                 # Embedded UI for debugging agents visually
├── argi-bom/                    # Bill of Materials for dependency management
├── spring-boot-starters/              # Spring Boot Starters
│   ├── argi-starter-builtin-nodes/     # Built-in workflow nodes
│   └── argi-starter-graph-observation/ # Observability
├── tools/                             # Build and linting tools
└── docs/                              # Documentation
```

This repository holds the core only. Optional integrations live in separate repositories:

- [Extensions](https://github.com/agentic-ai-java/argi-extensions) - model and document contracts, A2A Nacos, config Nacos, AgentScope, JDBC/Redis/MongoDB graph persistence, the Docker code executor, and the tool-call sandbox.
- [Examples](https://github.com/agentic-ai-java/argi-examples/tree/main/examples) - chatbot, multi-agent, and graph engineering samples.

Since `2.1.0` the core no longer imports the Extensions BOM. Applications that use optional integrations must import both `argi-bom` and the matching Extensions BOM.

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
./mvnw -pl :argi-agent-framework -B package -DskipTests=true

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

- Follow the repository's existing Java formatting and Checkstyle rules.
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
3.  **Dependencies**: Check `argi-bom` or parent pom for version management.
4.  **Makefile**: Use the Makefile in the root for project maintenance tasks (linting, license checks).
5.  **Structure**: When adding new features, prefer creating or updating modules within `argi-agent-framework` or `spring-boot-starters` depending on the scope.

## Important Links

- **Issues**: [https://github.com/agentic-ai-java/argi/issues](https://github.com/agentic-ai-java/argi/issues)
- **Source**: [https://github.com/agentic-ai-java/argi](https://github.com/agentic-ai-java/argi)
- **Contributing**: [CONTRIBUTING.md](CONTRIBUTING.md)
