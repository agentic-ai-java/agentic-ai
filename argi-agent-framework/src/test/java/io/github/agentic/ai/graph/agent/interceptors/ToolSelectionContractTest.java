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
import io.github.agentic.ai.graph.agent.interceptor.toolselection.ToolSelectionInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolSelectionContractTest {

	@Test
	void mandatoryToolSurvivesTheLimit() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"selected_tool\"]}", 1, "weather_tool");
		assertEquals(List.of("weather_tool"), select(interceptor, List.of("selected_tool", "weather_tool", "other_tool")));
	}

	@Test
	void mandatoryToolsReserveSlotsBeforeRankedTools() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"a\",\"b\"]}", 2, "c");
		assertEquals(List.of("a", "c"), select(interceptor, List.of("a", "b", "c", "d")));
	}

	@Test
	void mandatoryToolsTakePrecedenceWhenTheyExceedTheLimit() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"selected_tool\"]}", 1, "weather_tool", "clock_tool");
		assertEquals(List.of("weather_tool", "clock_tool"),
				select(interceptor, List.of("selected_tool", "weather_tool", "clock_tool")));
	}

	@Test
	void selectionRankDeterminesWhichToolsFitTheLimit() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"c\",\"b\",\"a\"]}", 2);
		assertEquals(List.of("b", "c"), select(interceptor, List.of("a", "b", "c", "d")));
	}

	@Test
	void unavailableNamesDoNotConsumeSlots() {
		ToolSelectionInterceptor interceptor = selection("{\"tools\":[\"missing\",\"a\"]}", 1, "missing");
		assertEquals(List.of("a"), select(interceptor, List.of("a", "b", "c")));
	}

	@Test
	void requestWithinTheLimitIsNotFiltered() {
		SelectionModel model = new SelectionModel("{\"tools\":[]}");
		ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder().selectionModel(model).maxTools(2).build();
		ModelRequest request = request(List.of("a", "b"));
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request, filtered -> {
			captured.set(filtered);
			return ModelResponse.of(new AssistantMessage("done"));
		});
		assertSame(request, captured.get());
		assertEquals(0, model.calls);
	}

	private static ToolSelectionInterceptor selection(String json, int max, String... mandatory) {
		return ToolSelectionInterceptor.builder().selectionModel(new SelectionModel(json)).maxTools(max)
				.alwaysInclude(mandatory).build();
	}

	private static ModelRequest request(List<String> tools) {
		return ModelRequest.builder().messages(List.of(new UserMessage("Choose relevant tools"))).tools(tools).build();
	}

	private static List<String> select(ToolSelectionInterceptor interceptor, List<String> tools) {
		AtomicReference<ModelRequest> captured = new AtomicReference<>();
		interceptor.interceptModel(request(tools), filtered -> {
			captured.set(filtered);
			return ModelResponse.of(new AssistantMessage("done"));
		});
		return captured.get().getTools();
	}

	private static final class SelectionModel implements ChatModel {

		private final String json;

		private int calls;

		private SelectionModel(String json) {
			this.json = json;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			calls++;
			return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
		}
	}
}
