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
 * Versioned, immutable plan data, independent of Todo state and tool dispatch journals.
 * The wire schema version is distinct from the application-owned plan revision. Step IDs
 * identify logical steps and must be retained by the application across revisions. This
 * contract does not schedule or execute work.
 */
public record PlanSpec(int schemaVersion, long revision, String objective, List<PlanStep> steps) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	public PlanSpec {
		if (steps != null) {
			steps = Collections.unmodifiableList(new ArrayList<>(steps));
		}
	}

}
