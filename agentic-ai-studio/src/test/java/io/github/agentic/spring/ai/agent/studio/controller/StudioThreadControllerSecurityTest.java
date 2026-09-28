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
package io.github.agentic.spring.ai.agent.studio.controller;

import io.github.agentic.spring.ai.agent.studio.dto.ListThreadsResponse;
import io.github.agentic.spring.ai.agent.studio.dto.Thread;
import io.github.agentic.spring.ai.agent.studio.loader.GraphLoader;
import io.github.agentic.spring.ai.agent.studio.loader.AgentLoader;
import io.github.agentic.spring.ai.agent.studio.service.ThreadService;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StudioThreadControllerSecurityTest {

	@Test
	void discoveryEndpointsRejectAnonymousRequestsBeforeLoaderAccess() throws Exception {
		AgentLoader agentLoader = mock(AgentLoader.class);
		GraphLoader graphLoader = mock(GraphLoader.class);
		when(agentLoader.listAgents()).thenReturn(List.of());
		when(graphLoader.listGraphs()).thenReturn(List.of());
		AgentController agentController = new AgentController(agentLoader);
		GraphController graphController = new GraphController(graphLoader);
		clearInvocations(agentLoader, graphLoader);

		standaloneSetup(agentController).build()
			.perform(get("/list-apps"))
			.andExpect(status().isForbidden());
		standaloneSetup(graphController).build()
			.perform(get("/list-graphs"))
			.andExpect(status().isForbidden());

		verifyNoInteractions(agentLoader, graphLoader);
	}

	@Test
	void threadEndpointsRejectAnonymousRequestsByDefaultBeforeServiceAccess() throws Exception {
		ThreadService threadService = mock(ThreadService.class);
		var mockMvc = standaloneSetup(new ThreadController(threadService)).build();

		mockMvc.perform(get("/apps/assistant/users/alice/threads/thread-1"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/apps/assistant/users/alice/threads"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/apps/assistant/users/alice/threads/thread-1")
				.contentType(APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/apps/assistant/users/alice/threads")
				.contentType(APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(delete("/apps/assistant/users/alice/threads/thread-1"))
			.andExpect(status().isForbidden());

		verifyNoInteractions(threadService);
	}

	@Test
	void threadEndpointsAllowRequestsWhenConfiguredTokenMatches() throws Exception {
		ThreadService threadService = mock(ThreadService.class);
		Thread thread = Thread.builder("thread-1")
			.appName("assistant")
			.userId("alice")
			.values(Map.of())
			.build();
		when(threadService.listThreads("assistant", "alice"))
			.thenReturn(Mono.just(ListThreadsResponse.of(List.of(thread))));

		standaloneSetup(new ThreadController(threadService, new StudioExecutionAccess("secret"))).build()
			.perform(get("/apps/assistant/users/alice/threads")
				.header(StudioExecutionAccess.TOKEN_HEADER, "secret"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].thread_id").value("thread-1"));

		verify(threadService).listThreads("assistant", "alice");
	}

	@Test
	void graphThreadEndpointsRejectAnonymousRequestsByDefaultBeforeGraphOrServiceAccess() throws Exception {
		GraphLoader graphLoader = mock(GraphLoader.class);
		ThreadService threadService = mock(ThreadService.class);
		var mockMvc = standaloneSetup(new GraphThreadController(graphLoader, threadService)).build();

		mockMvc.perform(get("/graphs/research/users/alice/threads/thread-1"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/graphs/research/users/alice/threads"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/graphs/research/users/alice/threads/thread-1")
				.contentType(APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/graphs/research/users/alice/threads")
				.contentType(APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(delete("/graphs/research/users/alice/threads/thread-1"))
			.andExpect(status().isForbidden());

		verifyNoInteractions(graphLoader, threadService);
	}

	@Test
	void graphThreadEndpointsAllowRequestsWhenConfiguredTokenMatches() throws Exception {
		GraphLoader graphLoader = mock(GraphLoader.class);
		ThreadService threadService = mock(ThreadService.class);
		Thread thread = Thread.builder("thread-1")
			.appName("graph:research")
			.userId("alice")
			.values(Map.of())
			.build();
		when(graphLoader.listGraphs()).thenReturn(List.of("research"));
		when(threadService.listThreads("graph:research", "alice"))
			.thenReturn(Mono.just(ListThreadsResponse.of(List.of(thread))));

		standaloneSetup(new GraphThreadController(graphLoader, threadService, new StudioExecutionAccess("secret"))).build()
			.perform(get("/graphs/research/users/alice/threads")
				.header(StudioExecutionAccess.TOKEN_HEADER, "secret"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].thread_id").value("thread-1"));

		verify(graphLoader).listGraphs();
		verify(threadService).listThreads("graph:research", "alice");
	}

}
