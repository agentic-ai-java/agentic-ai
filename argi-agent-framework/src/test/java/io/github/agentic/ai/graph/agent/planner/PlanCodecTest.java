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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.fixture;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.plan;
import static io.github.agentic.ai.graph.agent.planner.PlannerFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanCodecTest {

	private final PlanCodec codec = new PlanCodec();

	@ParameterizedTest
	@ValueSource(strings = { "serial", "parallel" })
	void versionOneFixturesRoundTrip(String name) throws Exception {
		String json = fixture(name);
		PlanSpec decoded = this.codec.decode(json);
		assertEquals(PlanCodec.MAPPER.readTree(json), PlanCodec.MAPPER.readTree(this.codec.encode(decoded)));
		assertEquals(decoded, this.codec.decode(this.codec.encode(decoded)));
		assertEquals(1,
				PlanCodec.MAPPER.readTree(this.codec.jsonSchema())
					.get("properties")
					.get("schemaVersion")
					.get("const")
					.asInt());
	}

	@ParameterizedTest
	@EnumSource(PlanStep.Status.class)
	void historicalStatusAndOutcomesArePreservedButNotAuthorized(PlanStep.Status status) {
		PlanStep original = step("stable-id");
		PlanStep stored = new PlanStep(original.stepId(), original.objective(), original.dependencies(),
				original.executionTarget(), original.inputSchema(), original.outputSchema(),
				original.completionCriteria(), status, "\"done\"", "diagnostic");
		PlanSpec persisted = new PlanSpec(1, Long.MAX_VALUE, "Stored objective", List.of(stored));
		assertEquals(persisted, this.codec.decode(this.codec.encode(persisted)));
		assertThrows(PlanValidationException.class, () -> PlannerFixtures.validator().validateGenerated(persisted));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "null", "[]", "{}", "not JSON", "{", "```json\n{}\n```", "{} {}" })
	void invalidWireDataIsRejected(String json) {
		assertThrows(PlanValidationException.class, () -> this.codec.decode(json));
	}

	@Test
	void nullInputIsRejectedWithCause() {
		assertNotNull(assertThrows(PlanValidationException.class, () -> this.codec.decode(null)).getCause());
		assertThrows(PlanValidationException.class, () -> this.codec.encode(null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "schemaVersion", "revision", "objective", "steps" })
	void requiredPlanFieldsCannotBeOmitted(String field) throws Exception {
		var json = (tools.jackson.databind.node.ObjectNode) PlanCodec.MAPPER.readTree(fixture("serial"));
		json.remove(field);
		assertThrows(PlanValidationException.class, () -> this.codec.decode(json.toString()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "stepId", "objective", "dependencies", "executionTarget", "inputSchema", "outputSchema",
			"completionCriteria", "status", "result", "error" })
	void requiredStepFieldsCannotBeOmitted(String field) throws Exception {
		var json = PlanCodec.MAPPER.readTree(fixture("serial"));
		((tools.jackson.databind.node.ObjectNode) json.get("steps").get(0)).remove(field);
		assertThrows(PlanValidationException.class, () -> this.codec.decode(json.toString()));
	}

	@Test
	void wrongTypesVersionsUnknownFieldsAndDuplicateKeysAreRejected() throws Exception {
		String json = fixture("serial");
		for (String changed : List.of(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
				json.replace("\"revision\": 1", "\"revision\": 0"),
				json.replace("\"revision\": 1", "\"revision\": 1.5"),
				json.replace("\"revision\": 1", "\"revision\": \"1\""),
				json.replace("\"revision\": 1", "\"revision\": 9223372036854775808"),
				json.replace("\"PENDING\"", "\"invented\""),
				json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"extra\": true"),
				json.replace("\"stepId\": \"research\"", "\"stepId\": \"research\", \"permission\": \"all\""),
				json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"))) {
			assertThrows(PlanValidationException.class, () -> this.codec.decode(changed));
		}
	}

	@Test
	void listsAreDefensiveSnapshots() {
		List<String> dependencies = new ArrayList<>(List.of("first"));
		PlanStep second = new PlanStep("second", "Second", dependencies, "tool:search", PlannerFixtures.INPUT,
				PlannerFixtures.OUTPUT, "Finish second", PlanStep.Status.PENDING, null, null);
		List<PlanStep> steps = new ArrayList<>(List.of(step("first"), second));
		PlanSpec spec = new PlanSpec(1, 1, "Snapshot", steps);
		dependencies.clear();
		steps.clear();
		assertEquals(List.of("first"), second.dependencies());
		assertEquals(2, spec.steps().size());
		assertThrows(UnsupportedOperationException.class, () -> spec.steps().clear());
		assertThrows(UnsupportedOperationException.class, () -> second.dependencies().clear());
		assertThrows(PlanValidationException.class, () -> this.codec.encode(new PlanSpec(1, 1, "No steps", null)));
		assertThrows(PlanValidationException.class,
				() -> this.codec.encode(plan(new PlanStep("empty", "Empty", null, "tool:search", PlannerFixtures.INPUT,
						PlannerFixtures.OUTPUT, "Finish", PlanStep.Status.PENDING, null, null))));
	}

}
