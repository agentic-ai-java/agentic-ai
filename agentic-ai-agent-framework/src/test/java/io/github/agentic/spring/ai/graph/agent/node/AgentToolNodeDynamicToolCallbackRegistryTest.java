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
package io.github.agentic.spring.ai.graph.agent.node;

import io.github.agentic.spring.ai.graph.OverAllState;
import io.github.agentic.spring.ai.graph.RunnableConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the in-memory dynamic tool callback registry on {@link AgentToolNode}.
 *
 * <p>
 * Covers the Human-in-the-Loop resumption scenario reported in issue #35: skill tools are
 * disclosed dynamically at model-call time and passed to the tool node through
 * {@code config.context()}, which is cleared when a subgraph resumes after an interruption.
 * The registry (shared with {@link AgentLlmNode} via the agent builder) keeps the disclosed
 * callbacks resolvable across resumption.
 * </p>
 *
 * @author Kal'tsit
 * @since 1.0.0
 */
@DisplayName("AgentToolNode Dynamic Tool Callback Registry Tests")
class AgentToolNodeDynamicToolCallbackRegistryTest {

	private static final ToolExecutionExceptionProcessor DEFAULT_PROCESSOR = e -> "Error: " + e.getMessage();

	private ToolCallback queryTool() {
		return FunctionToolCallback.builder("queryTool", (QueryInput input) -> "result:" + input.q())
			.description("Query tool")
			.inputType(QueryInput.class)
			.build();
	}

	record QueryInput(String q) {
	}

	private AgentToolNode buildNode() {
		return AgentToolNode.builder()
			.agentName("test-agent")
			.toolExecutionTimeout(Duration.ofMinutes(5))
			.toolExecutionExceptionProcessor(DEFAULT_PROCESSOR)
			.build();
	}

	private OverAllState stateWithToolCall(String toolName, String arguments) {
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", toolName, arguments)))
			.build();
		Map<String, Object> stateData = new HashMap<>();
		stateData.put("messages", new ArrayList<>(List.of(assistantMessage)));
		return new OverAllState(stateData);
	}

	@Test
	@DisplayName("Dynamic registry resolves a tool when the config context is empty (HITL resume scenario)")
	void dynamicRegistryResolvesToolWhenConfigContextIsEmpty() throws Exception {
		AgentToolNode node = buildNode();
		node.registerDynamicToolCallbacks(List.of(queryTool()));

		// Simulate a resumed run: a fresh config whose context no longer carries the
		// dynamically disclosed tool callbacks.
		RunnableConfig config = RunnableConfig.builder().threadId("test-thread").build();

		Map<String, Object> result = node.apply(stateWithToolCall("queryTool", "{\"q\":\"hello\"}"), config);

		ToolResponseMessage responseMessage = (ToolResponseMessage) result.get("messages");
		assertThat(responseMessage.getResponses()).hasSize(1);
		assertThat(responseMessage.getResponses().get(0).responseData()).contains("result:hello");
	}

	@Test
	@DisplayName("A tool absent from the registry is reported as unavailable")
	void toolAbsentFromRegistryIsReportedUnavailable() throws Exception {
		AgentToolNode node = buildNode();

		RunnableConfig config = RunnableConfig.builder().threadId("test-thread").build();

		Map<String, Object> result = node.apply(stateWithToolCall("unknownTool", "{}"), config);

		ToolResponseMessage responseMessage = (ToolResponseMessage) result.get("messages");
		assertThat(responseMessage.getResponses()).hasSize(1);
		assertThat(responseMessage.getResponses().get(0).responseData()).contains("Tool not available: unknownTool");
	}

}
