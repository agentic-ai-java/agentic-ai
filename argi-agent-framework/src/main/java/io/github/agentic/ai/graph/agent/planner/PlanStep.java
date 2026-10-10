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
import java.util.Collections;
import java.util.List;

/**
 * A logical step referring to a registered capability, not executable code. Input/output
 * schemas are JSON-encoded, self-contained Draft 2020-12 schemas. Completion criteria are
 * descriptive requirements, not proof of completion. Result is JSON-encoded output; error
 * is a diagnostic string. Only a future trusted executor may populate execution outcomes.
 */
public record PlanStep(String stepId, String objective, List<String> dependencies, String executionTarget,
		String inputSchema, String outputSchema, String completionCriteria, Status status, String result,
		String error) {

	public PlanStep {
		if (dependencies != null) {
			dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
		}
	}

	/** Execution lifecycle values; generated plans may contain only PENDING. */
	public enum Status {

		PENDING, RUNNING, COMPLETED, FAILED, SKIPPED

	}

}
