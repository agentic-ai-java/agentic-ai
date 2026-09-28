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
package io.github.agentic.spring.ai.graph.node;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.agentic.spring.ai.graph.OverAllState;
import io.github.agentic.spring.ai.graph.state.strategy.ReplaceStrategy;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentNode}.
 */
class AgentNodeTest {

	/**
	 * Records every {@link Prompt} it is asked to answer, and optionally fails.
	 */
	private static final class RecordingChatModel implements ChatModel {

		private final List<Prompt> prompts = new ArrayList<>();

		private final AtomicInteger calls = new AtomicInteger();

		private String reply = "model-reply";

		private RuntimeException failure;

		@Override
		public ChatResponse call(Prompt prompt) {
			this.calls.incrementAndGet();
			this.prompts.add(prompt);
			if (this.failure != null) {
				throw this.failure;
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage(this.reply))));
		}

		private RecordingChatModel failingWith(RuntimeException failure) {
			this.failure = failure;
			return this;
		}

		private RecordingChatModel replyingWith(String reply) {
			this.reply = reply;
			return this;
		}

	}

	private static OverAllState stateOf(Map<String, Object> entries) {
		OverAllState state = new OverAllState();
		entries.forEach((k, v) -> {
			state.registerKeyAndStrategy(k, new ReplaceStrategy());
			state.updateState(Map.of(k, v));
		});
		return state;
	}

	private static ToolCallback toolCallback(boolean returnDirect) {
		ToolMetadata metadata = ToolMetadata.builder().returnDirect(returnDirect).build();
		ToolDefinition definition = ToolDefinition.builder()
			.name("echo")
			.description("echo the input")
			.inputSchema("{\"type\":\"object\",\"properties\":{}}")
			.build();
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public ToolMetadata getToolMetadata() {
				return metadata;
			}

			@Override
			public String call(String toolInput) {
				return toolInput;
			}

			@Override
			public String call(String toolInput, @Nullable ToolContext toolContext) {
				return toolInput;
			}
		};
	}

	private static ToolCallback[] preparedCallbacks(AgentNode node) {
		Field field = ReflectionUtils.findField(AgentNode.class, "toolCallbacks");
		ReflectionUtils.makeAccessible(field);
		return (ToolCallback[]) ReflectionUtils.getField(field, node);
	}

	private static AgentNode.Builder nodeBuilder(ChatModel chatModel) {
		return AgentNode.builder().chatClient(ChatClient.create(chatModel)).userPrompt("question").outputKey("out");
	}

	@Test
	void writesModelContentToOutputKey() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel().replyingWith("hello");

		Map<String, Object> result = nodeBuilder(chatModel).build().apply(stateOf(Map.of()));

		assertEquals(Map.of("out", "hello"), result);
		assertEquals(1, chatModel.calls.get());
	}

	@Test
	void defaultsToAgentOutputKey() throws Exception {
		AgentNode node = AgentNode.builder()
			.chatClient(ChatClient.create(new RecordingChatModel()))
			.userPrompt("question")
			.build();

		assertTrue(node.apply(stateOf(Map.of())).containsKey("agent_output"));
	}

	@Test
	void rendersPromptTemplatesAgainstState() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel();

		AgentNode.builder()
			.chatClient(ChatClient.create(chatModel))
			.systemPrompt("You are {role}")
			.userPrompt("Tell me about {topic}")
			.outputKey("out")
			.build()
			.apply(stateOf(Map.of("role", "a guide", "topic", "graphs")));

		List<String> texts = chatModel.prompts.get(0).getInstructions().stream().map(Message::getText).toList();
		assertTrue(texts.contains("You are a guide"), () -> "rendered system prompt missing, got " + texts);
		assertTrue(texts.contains("Tell me about graphs"), () -> "rendered user prompt missing, got " + texts);
	}

	@Test
	void requiresChatClient() {
		assertThrows(IllegalArgumentException.class, () -> AgentNode.builder().userPrompt("question").build());
	}

	@Test
	void requiresAtLeastOnePrompt() {
		assertThrows(IllegalArgumentException.class,
				() -> AgentNode.builder().chatClient(ChatClient.create(new RecordingChatModel())).build());
	}

	@Test
	void rejectsNegativeMaxRetries() {
		assertThrows(IllegalArgumentException.class,
				() -> nodeBuilder(new RecordingChatModel()).maxRetries(-1).build());
	}

	@Test
	void systemPromptAloneIsEnough() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel();

		Map<String, Object> result = AgentNode.builder()
			.chatClient(ChatClient.create(chatModel))
			.systemPrompt("You are a guide")
			.outputKey("out")
			.build()
			.apply(stateOf(Map.of()));

		assertEquals(Map.of("out", "model-reply"), result);
	}

	@Test
	void userPromptAloneIsEnough() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel();

		Map<String, Object> result = nodeBuilder(chatModel).build().apply(stateOf(Map.of()));

		assertEquals(Map.of("out", "model-reply"), result);
	}

	@Test
	void buildsWithoutToolCallbacks() throws Exception {
		AgentNode node = nodeBuilder(new RecordingChatModel()).build();

		assertEquals(0, preparedCallbacks(node).length);
		assertEquals(Map.of("out", "model-reply"), node.apply(stateOf(Map.of())));
	}

	@Test
	void toolCallingStrategyForcesReturnDirect() {
		ToolCallback source = toolCallback(false);

		AgentNode node = nodeBuilder(new RecordingChatModel()).strategy(AgentNode.Strategy.TOOL_CALLING)
			.toolCallbacks(new ToolCallback[] { source })
			.build();

		ToolCallback prepared = preparedCallbacks(node)[0];
		assertTrue(prepared.getToolMetadata().returnDirect());
		assertEquals("echo", prepared.getToolDefinition().name());
	}

	@Test
	void reactStrategyLeavesToolCallbacksUntouched() {
		ToolCallback source = toolCallback(false);

		AgentNode node = nodeBuilder(new RecordingChatModel()).strategy(AgentNode.Strategy.REACT)
			.toolCallbacks(new ToolCallback[] { source })
			.build();

		assertSame(source, preparedCallbacks(node)[0]);
		assertFalse(preparedCallbacks(node)[0].getToolMetadata().returnDirect());
	}

	@Test
	void toolCallingStrategyKeepsCallbacksThatAlreadyReturnDirect() {
		ToolCallback source = toolCallback(true);

		AgentNode node = nodeBuilder(new RecordingChatModel()).strategy(AgentNode.Strategy.TOOL_CALLING)
			.toolCallbacks(new ToolCallback[] { source })
			.build();

		assertSame(source, preparedCallbacks(node)[0]);
	}

	@Test
	void maxRetriesIsRetryCountNotAttemptCount() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel().failingWith(new IllegalStateException("boom"));

		nodeBuilder(chatModel).maxRetries(3).build().apply(stateOf(Map.of()));

		assertEquals(4, chatModel.calls.get(), "maxRetries=3 means one attempt plus three retries");
	}

	@Test
	void defaultMaxRetriesIsOne() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel().failingWith(new IllegalStateException("boom"));

		nodeBuilder(chatModel).build().apply(stateOf(Map.of()));

		assertEquals(2, chatModel.calls.get(), "the default maxRetries=1 means two model invocations");
	}

	@Test
	void deprecatedMaxIterationsStillSetsTheRetryCount() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel().failingWith(new IllegalStateException("boom"));

		nodeBuilder(chatModel).maxIterations(2).build().apply(stateOf(Map.of()));

		assertEquals(3, chatModel.calls.get());
	}

	@Test
	void swallowsModelFailureByDefault() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel().failingWith(new IllegalStateException("boom"));

		assertEquals(Map.of("out", ""), nodeBuilder(chatModel).build().apply(stateOf(Map.of())));
	}

	@Test
	void propagatesModelFailureWhenOptedIn() {
		RecordingChatModel chatModel = new RecordingChatModel().failingWith(new IllegalStateException("boom"));
		AgentNode node = nodeBuilder(chatModel).throwOnModelError(true).build();

		Exception thrown = assertThrows(Exception.class, () -> node.apply(stateOf(Map.of())));

		assertTrue(containsMessage(thrown, "boom"), () -> "original cause lost: " + thrown);
	}

	@Test
	void retriesWhenModelReturnsNullContent() throws Exception {
		RecordingChatModel chatModel = new RecordingChatModel().replyingWith(null);

		assertEquals(Map.of("out", ""), nodeBuilder(chatModel).maxRetries(2).build().apply(stateOf(Map.of())));
		assertEquals(3, chatModel.calls.get());
	}

	private static boolean containsMessage(Throwable throwable, String text) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

}
