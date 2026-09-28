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

import io.github.agentic.spring.ai.graph.state.strategy.AppendStrategy;
import io.github.agentic.spring.ai.graph.stream.LLmNodeAction;
import io.github.agentic.spring.ai.graph.streaming.OutputType;
import io.github.agentic.spring.ai.graph.streaming.StreamingOutput;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.agentic.spring.ai.graph.StateGraph.END;
import static io.github.agentic.spring.ai.graph.StateGraph.START;
import static io.github.agentic.spring.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateGraphStreamTest {

	@Test
	void usageOnlyChunkDoesNotAbortStreamCompletion() throws Exception {
		CompiledGraph graph = buildGraph(new UsageOnlyStreamingChatModel());

		List<NodeOutput> outputs = graph.stream(Map.of(OverAllState.DEFAULT_INPUT_KEY, "test"))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertNotNull(outputs, "stream outputs should not be null");
		assertTrue(outputs.stream().anyMatch(output -> output.tokenUsage().getTotalTokens() == 6),
				"usage-only chunks should be emitted with token usage");
		assertTrue(outputs.stream().anyMatch(StateGraphStreamTest::isFinishedOutput),
				"stream should emit a graph-node-finished marker");
		assertEquals("hello world", extractLastAssistantText(outputs.get(outputs.size() - 1)),
				"final state should keep the completed assistant message");
	}

	private static CompiledGraph buildGraph(ChatModel chatModel) throws Exception {
		StateGraph stateGraph = new StateGraph(() -> {
			Map<String, KeyStrategy> keyStrategies = new HashMap<>();
			keyStrategies.put("messages", new AppendStrategy());
			return keyStrategies;
		}).addNode("llmNode", node_async(new LLmNodeAction(chatModel, "llmNode")))
			.addEdge(START, "llmNode")
			.addEdge("llmNode", END);
		return stateGraph.compile();
	}

	private static boolean isFinishedOutput(NodeOutput output) {
		return output instanceof StreamingOutput<?> streamingOutput
				&& streamingOutput.getOutputType() == OutputType.GRAPH_NODE_FINISHED;
	}

	private static String extractLastAssistantText(NodeOutput lastOutput) {
		Object messages = lastOutput.state().value("messages").orElse(List.of());
		AssistantMessage lastAssistant = null;
		if (messages instanceof Iterable<?> iterable) {
			for (Object message : iterable) {
				if (message instanceof AssistantMessage assistantMessage) {
					lastAssistant = assistantMessage;
				}
			}
		}
		return lastAssistant != null ? lastAssistant.getText() : "";
	}

	private static ChatResponse usageOnlyResponse(Usage usage) {
		try {
			Class<?> metadataClass = Class.forName("org.springframework.ai.chat.metadata.ChatResponseMetadata");
			Object metadata = chatResponseMetadata(metadataClass, usage);
			Constructor<ChatResponse> constructor = ChatResponse.class.getConstructor(List.class, metadataClass);
			return constructor.newInstance(List.of(new Generation(null, ChatGenerationMetadata.NULL)), metadata);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to build usage-only ChatResponse", ex);
		}
	}

	private static Object chatResponseMetadata(Class<?> metadataClass, Usage usage) throws ReflectiveOperationException {
		try {
			Method builderMethod = metadataClass.getMethod("builder");
			Object builder = builderMethod.invoke(null);
			Method usageMethod = builder.getClass().getMethod("usage", Usage.class);
			Object builderWithUsage = usageMethod.invoke(builder, usage);
			Method buildMethod = builderWithUsage.getClass().getMethod("build");
			return buildMethod.invoke(builderWithUsage);
		}
		catch (NoSuchMethodException ignored) {
			// Try older metadata construction styles below.
		}

		try {
			Constructor<?> constructor = metadataClass.getConstructor(Usage.class);
			return constructor.newInstance(usage);
		}
		catch (NoSuchMethodException ignored) {
			// Try static factory methods below.
		}

		for (Method method : metadataClass.getMethods()) {
			if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
					&& method.getParameterTypes()[0].isAssignableFrom(Usage.class)
					&& metadataClass.isAssignableFrom(method.getReturnType())) {
				return method.invoke(null, usage);
			}
		}
		throw new NoSuchMethodException("No ChatResponseMetadata factory for Usage");
	}

	private static ChatResponse textResponse(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static final class UsageOnlyStreamingChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			return textResponse("hello world");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.just(textResponse("hello"), usageOnlyResponse(new TestUsage(1, 5, 6)), textResponse(" world"));
		}

	}

	private static final class TestUsage implements Usage {

		private final Integer promptTokens;

		private final Integer completionTokens;

		private final Integer totalTokens;

		private TestUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
			this.promptTokens = promptTokens;
			this.completionTokens = completionTokens;
			this.totalTokens = totalTokens;
		}

		@Override
		public Integer getPromptTokens() {
			return this.promptTokens;
		}

		@Override
		public Integer getCompletionTokens() {
			return this.completionTokens;
		}

		@Override
		public Integer getTotalTokens() {
			return this.totalTokens;
		}

		@Override
		public Map<String, Object> getNativeUsage() {
			return Map.of("prompt_tokens", this.promptTokens, "completion_tokens", this.completionTokens,
					"total_tokens", this.totalTokens);
		}

	}

}
