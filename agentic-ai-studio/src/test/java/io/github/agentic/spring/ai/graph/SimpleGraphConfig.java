/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.agentic.spring.ai.graph;

import io.github.agentic.spring.ai.graph.exception.GraphStateException;
import io.github.agentic.spring.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.spring.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static io.github.agentic.spring.ai.graph.StateGraph.END;
import static io.github.agentic.spring.ai.graph.StateGraph.START;
import static io.github.agentic.spring.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Minimal graph configuration for Studio integration tests. Creates a simple echo-style graph
 * that accepts "messages" input and passes through without requiring ChatModel or external services.
 */
@Configuration
public class SimpleGraphConfig {

	@Bean
	public CompiledGraph simpleWorkflowGraph() throws GraphStateException {
		KeyStrategyFactory keyFactory = () -> {
			Map<String, KeyStrategy> strategies = new HashMap<>();
			strategies.put("messages", new AppendStrategy(false));
			strategies.put("result", new ReplaceStrategy());
			return strategies;
		};

		StateGraph graph = new StateGraph("simple_workflow", keyFactory)
				.addNode("echo", node_async(state -> {
					Object messages = state.value("messages").orElse(null);
					return Map.of("result", "echo: " + (messages != null ? messages.toString() : "empty"));
				}))
				.addEdge(START, "echo")
				.addEdge("echo", END);

		return graph.compile();
	}

}
