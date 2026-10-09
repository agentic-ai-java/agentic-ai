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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

final class PlannerFixtures {

	static final String INPUT = "{\"type\":\"object\"}";

	static final String OUTPUT = "{\"type\":\"string\"}";

	static final String OBJECTIVE = "Research and summarize";

	static final PlanCapability SEARCH = new PlanCapability("tool:search", "Search documents", INPUT, OUTPUT);

	static final PlanCapability WRITER = new PlanCapability("agent:writer", "Write a summary", INPUT, OUTPUT);

	private PlannerFixtures() {
	}

	static PlanValidator validator() {
		return new PlanValidator(List.of(SEARCH, WRITER));
	}

	static PlanStep step(String id, String... dependencies) {
		return new PlanStep(id, "Perform " + id, List.of(dependencies), SEARCH.executionTarget(), INPUT, OUTPUT,
				"Produce a relevant document", PlanStep.Status.PENDING, null, null);
	}

	static PlanSpec plan(PlanStep... steps) {
		return new PlanSpec(1, 1, OBJECTIVE, List.of(steps));
	}

	static String fixture(String name) throws IOException {
		try (var stream = new ClassPathResource("planner/" + name + "-v1.json").getInputStream()) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
