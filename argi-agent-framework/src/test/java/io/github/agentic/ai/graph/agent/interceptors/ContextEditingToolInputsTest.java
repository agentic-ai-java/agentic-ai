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
package io.github.agentic.ai.graph.agent.interceptors;

import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.ModelResponse;
import io.github.agentic.ai.graph.agent.interceptor.contextediting.ContextEditingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ContextEditingToolInputsTest {

	@Test
	void defaultKeepsToolArgumentsWhileClearingResults() {
		assertInputsPreserved(edit(builder().build()));
	}

	@Test
	void explicitFalseKeepsToolArgumentsWhileClearingResults() {
		assertInputsPreserved(edit(builder().clearToolInputs(false).build()));
	}

	@Test
	void explicitTrueClearsToolArgumentsAndResults() {
		List<Message> edited = edit(builder().clearToolInputs(true).build());
		assertEquals("[cleared]", toolArguments(edited.get(1)));
		assertEquals("[cleared]", toolArguments(edited.get(3)));
		assertResultsCleared(edited);
	}

	@Test
	void excludedToolKeepsItsArgumentsAndResult() {
		List<Message> edited = edit(builder().clearToolInputs(true).excludeTools("search").build());
		assertEquals("{\"q\":\"first\"}", toolArguments(edited.get(1)));
		assertEquals("{\"q\":\"second\"}", toolArguments(edited.get(3)));
		assertEquals("first result", toolResult(edited.get(2)));
		assertEquals("second result", toolResult(edited.get(4)));
	}

	private static ContextEditingInterceptor.Builder builder() {
		return ContextEditingInterceptor.builder().trigger(1).keep(0).clearAtLeast(400)
				.tokenCounter(messages -> messages.stream().mapToInt(message -> message instanceof UserMessage ? 0 : 100).sum());
	}

	private static List<Message> edit(ContextEditingInterceptor interceptor) {
		List<Message> messages = List.of(new UserMessage("Use search results"),
				assistant("call-1", "{\"q\":\"first\"}"), result("call-1", "first result"),
				assistant("call-2", "{\"q\":\"second\"}"), result("call-2", "second result"));
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(ModelRequest.builder().messages(messages).build(), request -> {
			captured.set(request);
			return ModelResponse.of(new AssistantMessage("done"));
		});
		return captured.get().getMessages();
	}

	private static AssistantMessage assistant(String id, String arguments) {
		return AssistantMessage.builder().content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "search", arguments))).build();
	}

	private static ToolResponseMessage result(String id, String text) {
		return ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse(id, "search", text))).build();
	}

	private static String toolArguments(Message message) {
		return assertInstanceOf(AssistantMessage.class, message).getToolCalls().get(0).arguments();
	}

	private static String toolResult(Message message) {
		return assertInstanceOf(ToolResponseMessage.class, message).getResponses().get(0).responseData();
	}

	private static void assertInputsPreserved(List<Message> edited) {
		assertEquals("{\"q\":\"first\"}", toolArguments(edited.get(1)));
		assertEquals("{\"q\":\"second\"}", toolArguments(edited.get(3)));
		assertResultsCleared(edited);
	}

	private static void assertResultsCleared(List<Message> edited) {
		assertEquals("[cleared]", toolResult(edited.get(2)));
		assertEquals("[cleared]", toolResult(edited.get(4)));
	}
}
