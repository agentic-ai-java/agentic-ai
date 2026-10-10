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
package io.github.agentic.ai.graph.agent.planner;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.OBJECTIVE;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.fixture;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.plan;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArgiPlannerTest {

	private final ChatModel model = mock(ChatModel.class);

	private final PlanCodec codec = new PlanCodec();

	private final ArgiPlanner planner = new ArgiPlanner(this.model, PlannerFixtures.validator());

	@ParameterizedTest
	@ValueSource(strings = { "serial", "parallel" })
	void fixedModelProducesSchemaValidSerialAndParallelPlans(String name) throws Exception {
		String json = fixture(name);
		when(this.model.call(any(Prompt.class))).thenReturn(response(json));
		PlanSpec planned = this.planner.plan(decompose());
		assertEquals(this.codec.decode(json), planned);
		PlannerFixtures.validator().validateGenerated(planned);
		ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
		verify(this.model).call(prompt.capture());
		assertTrue(prompt.getValue().getSystemMessage().getText().contains("tool:search"));
		assertTrue(prompt.getValue().getSystemMessage().getText().contains("agent:writer"));
		assertTrue(prompt.getValue()
			.getSystemMessage()
			.getText()
			.contains("Maximum steps: 64; maximum dependency depth: 16"));
		assertTrue(prompt.getValue().getSystemMessage().getText().contains(this.codec.jsonSchema()));
		assertEquals(OBJECTIVE,
				PlanCodec.MAPPER.readTree(prompt.getValue().getUserMessage().getText()).get("objective").asString());
		assertTrue(((ToolCallingChatOptions) prompt.getValue().getOptions()).getToolCallbacks().isEmpty());
	}

	@Test
	void simpleKnownTaskBypassesModelAndNeverClaimsExecution() {
		PlanSpec first = this.planner
			.plan(new ArgiPlanner.Request("Search", 1, ArgiPlanner.Mode.DIRECT, "tool:search"));
		PlanSpec next = this.planner
			.plan(new ArgiPlanner.Request("Search again", 2, ArgiPlanner.Mode.DIRECT, "tool:search"));
		assertEquals("step-1", first.steps().get(0).stepId());
		assertEquals(first.steps().get(0).stepId(), next.steps().get(0).stepId());
		assertEquals(2, next.revision());
		assertEquals(PlanStep.Status.PENDING, first.steps().get(0).status());
		verify(this.model, never()).call(any(Prompt.class));
	}

	@Test
	void malformedRequestsAreRejectedBeforeModelCall() {
		for (ArgiPlanner.Request request : List.of(
				new ArgiPlanner.Request(" ", 1, ArgiPlanner.Mode.DIRECT, "tool:search"),
				new ArgiPlanner.Request("Task", 0, ArgiPlanner.Mode.DECOMPOSE, null),
				new ArgiPlanner.Request("Task", 1, null, null),
				new ArgiPlanner.Request("Task", 1, ArgiPlanner.Mode.DECOMPOSE, "tool:search"),
				new ArgiPlanner.Request("Task", 1, ArgiPlanner.Mode.DIRECT, null),
				new ArgiPlanner.Request("Task", 1, ArgiPlanner.Mode.DIRECT, "tool:unknown"))) {
			assertThrows(PlanValidationException.class, () -> this.planner.plan(request));
		}
		assertThrows(NullPointerException.class, () -> this.planner.plan(null));
		assertThrows(NullPointerException.class, () -> new ArgiPlanner(null, PlannerFixtures.validator()));
		assertThrows(NullPointerException.class, () -> new ArgiPlanner(this.model, null));
		verify(this.model, never()).call(any(Prompt.class));
	}

	@Test
	void invalidPlansFailBeforeAnyRegisteredToolIsInvoked() throws Exception {
		ToolCallback tool = mock(ToolCallback.class);
		when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder()
			.name("search")
			.description("Search documents")
			.inputSchema(PlannerFixtures.INPUT)
			.build());
		ArgiPlanner onlySearch = new ArgiPlanner(this.model,
				new PlanValidator(List.of(PlanCapability.tool(tool, PlannerFixtures.OUTPUT))));
		String valid = this.codec.encode(plan(step("a")));
		for (String json : List.of(this.codec.encode(plan(step("same"), step("same"))),
				this.codec.encode(plan(step("a", "missing"))), this.codec.encode(plan(step("a", "b"), step("b", "a"))),
				valid.replace("tool:search", "tool:invented"), valid.replace("PENDING", "COMPLETED"),
				valid.replace("\"result\":null", "\"result\":\"done\""),
				valid.replace("\"error\":null", "\"error\":\"failed\""),
				valid.replace("\"revision\":1", "\"revision\":2"), valid.replace(OBJECTIVE, "Another task"), "{}",
				fixture("serial"))) {
			when(this.model.call(any(Prompt.class))).thenReturn(response(json));
			assertThrows(PlanValidationException.class, () -> onlySearch.plan(decompose()));
		}
		verify(tool).getToolDefinition();
		verifyNoMoreInteractions(tool);
	}

	@Test
	void noRetryOrExecutionOccursOnModelFailure() {
		RuntimeException failure = new IllegalStateException("Provider unavailable");
		when(this.model.call(any(Prompt.class))).thenThrow(failure);
		assertEquals(failure, assertThrows(IllegalStateException.class, () -> this.planner.plan(decompose())));
		verify(this.model).call(any(Prompt.class));
	}

	@Test
	void emptyMultipleAndToolCallResponsesAreRejected() {
		for (ChatResponse response : List.of(new ChatResponse(List.of()),
				new ChatResponse(List.of(new Generation(new AssistantMessage("{}")),
						new Generation(new AssistantMessage("{}")))),
				new ChatResponse(List.of(new Generation(AssistantMessage.builder()
					.content("{}")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "search", "{}")))
					.build()))),
				response(""))) {
			when(this.model.call(any(Prompt.class))).thenReturn(response);
			assertThrows(PlanValidationException.class, () -> this.planner.plan(decompose()));
		}
		when(this.model.call(any(Prompt.class))).thenReturn(null);
		assertThrows(PlanValidationException.class, () -> this.planner.plan(decompose()));
	}

	@Test
	void toolEnabledModelDefaultsCannotExecuteDuringPlanning() {
		ChatModel configured = mock(ChatModel.class);
		ToolCallback tool = mock(ToolCallback.class);
		when(configured.getOptions()).thenReturn(ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build());
		assertThrows(PlanValidationException.class, () -> new ArgiPlanner(configured, PlannerFixtures.validator()));
		verify(configured, never()).call(any(Prompt.class));
		when(configured.getOptions()).thenReturn(ToolCallingChatOptions.builder().toolCallbacks(List.of()).build());
		new ArgiPlanner(configured, PlannerFixtures.validator());
		verifyNoMoreInteractions(tool);
	}

	private static ArgiPlanner.Request decompose() {
		return new ArgiPlanner.Request(OBJECTIVE, 1, ArgiPlanner.Mode.DECOMPOSE, null);
	}

	private static ChatResponse response(String json) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
	}

}
