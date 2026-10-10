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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

/**
 * Fail-closed capability and DAG validation. Limits count steps and the longest path in
 * nodes (an independent step has depth 1). Capability registration is an application
 * trust boundary, not model-discovered authority. There are no execution callbacks here.
 */
public final class PlanValidator {

	public static final int DEFAULT_MAX_STEPS = 64;

	public static final int DEFAULT_MAX_DEPTH = 16;

	private final Map<String, PlanCapability> capabilities;

	private final Map<String, List<JsonNode>> contracts;

	private final int maxSteps;

	private final int maxDepth;

	private final PlanCodec codec = new PlanCodec();

	public PlanValidator(Collection<PlanCapability> capabilities) {
		this(capabilities, DEFAULT_MAX_STEPS, DEFAULT_MAX_DEPTH);
	}

	public PlanValidator(Collection<PlanCapability> capabilities, int maxSteps, int maxDepth) {
		require(maxSteps > 0 && maxDepth > 0, "Plan limits must be positive");
		require(capabilities != null && !capabilities.isEmpty(), "Register at least one capability");
		Map<String, PlanCapability> registry = new LinkedHashMap<>();
		Map<String, List<JsonNode>> schemas = new HashMap<>();
		for (PlanCapability capability : capabilities) {
			require(capability != null, "Capability must not be null");
			text(capability.executionTarget(), "executionTarget");
			text(capability.description(), "description");
			require(registry.putIfAbsent(capability.executionTarget(), capability) == null,
					"Duplicate capability target");
			schemas.put(capability.executionTarget(),
					List.of(contract(capability.inputSchema()), contract(capability.outputSchema())));
		}
		this.capabilities = Collections.unmodifiableMap(registry);
		this.contracts = Collections.unmodifiableMap(schemas);
		this.maxSteps = maxSteps;
		this.maxDepth = maxDepth;
	}

	public Collection<PlanCapability> capabilities() {
		return this.capabilities.values();
	}

	/** Resolve an exact registered key without invoking the target. */
	public PlanCapability resolve(String executionTarget) {
		PlanCapability capability = this.capabilities.get(executionTarget);
		require(capability != null, "Unregistered execution target: " + executionTarget);
		return capability;
	}

	public int maxSteps() {
		return this.maxSteps;
	}

	public int maxDepth() {
		return this.maxDepth;
	}

	/**
	 * Validate stored or application-created plan structure, but do not trust outcomes.
	 */
	public void validate(PlanSpec plan) {
		require(plan != null, "Plan must not be null");
		require(plan.steps() != null && plan.steps().size() <= this.maxSteps,
				"Plan exceeds the step limit or has no steps");
		this.codec.encode(plan);
		text(plan.objective(), "objective");
		Map<String, PlanStep> steps = new LinkedHashMap<>();
		for (PlanStep step : plan.steps()) {
			text(step.stepId(), "stepId");
			text(step.objective(), "step objective");
			text(step.completionCriteria(), "completionCriteria");
			require(steps.putIfAbsent(step.stepId(), step) == null, "Duplicate stepId: " + step.stepId());
			resolve(step.executionTarget());
			List<JsonNode> registered = this.contracts.get(step.executionTarget());
			require(registered.get(0).equals(readContract(step.inputSchema()))
					&& registered.get(1).equals(readContract(step.outputSchema())),
					"Step schemas differ from registered capability contracts: " + step.stepId());
		}
		validateDag(steps);
	}

	/** Model-generated data must never claim that work has already been performed. */
	public void validateGenerated(PlanSpec plan) {
		validate(plan);
		for (PlanStep step : plan.steps()) {
			require(step.status() == PlanStep.Status.PENDING && step.result() == null && step.error() == null,
					"Generated steps must be PENDING without result or error: " + step.stepId());
		}
	}

	private void validateDag(Map<String, PlanStep> steps) {
		Map<String, Integer> remaining = new HashMap<>();
		Map<String, Integer> depth = new HashMap<>();
		Map<String, List<String>> dependents = new HashMap<>();
		ArrayDeque<String> ready = new ArrayDeque<>();
		for (PlanStep step : steps.values()) {
			remaining.put(step.stepId(), step.dependencies().size());
			depth.put(step.stepId(), 1);
			if (step.dependencies().isEmpty()) {
				ready.add(step.stepId());
			}
			for (String dependency : step.dependencies()) {
				require(steps.containsKey(dependency), "Missing dependency: " + dependency);
				dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(step.stepId());
			}
		}
		// Iterative traversal also checks disconnected components and avoids stack
		// overflow.
		int visited = 0;
		while (!ready.isEmpty()) {
			String id = ready.remove();
			visited++;
			require(depth.get(id) <= this.maxDepth, "Plan exceeds the dependency depth limit");
			for (String dependent : dependents.getOrDefault(id, List.of())) {
				depth.merge(dependent, depth.get(id) + 1, Math::max);
				if (remaining.compute(dependent, (key, count) -> count - 1) == 0) {
					ready.add(dependent);
				}
			}
		}
		require(visited == steps.size(), "Plan dependencies contain a cycle");
	}

	private static JsonNode readContract(String json) {
		try {
			return PlanCodec.MAPPER.readTree(json);
		}
		catch (RuntimeException ex) {
			throw new PlanValidationException("Invalid capability schema JSON", ex);
		}
	}

	private static JsonNode contract(String json) {
		JsonNode node = readContract(json);
		require(node != null && (node.isObject() || node.isBoolean()),
				"A capability schema must be an object or boolean");
		var meta = PlanCodec.SCHEMAS.getSchema(SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.getDialectId()));
		require(meta.validate(node).isEmpty(), "Invalid Draft 2020-12 capability schema");
		// Keep schema resolution local. A descriptor is not permission to fetch
		// resources.
		ArrayDeque<JsonNode> pending = new ArrayDeque<>();
		pending.add(node);
		while (!pending.isEmpty()) {
			JsonNode current = pending.remove();
			if (current.isObject()) {
				for (String keyword : List.of("$ref", "$dynamicRef")) {
					JsonNode ref = current.get(keyword);
					require(ref == null || ref.asString().startsWith("#"),
							"Capability schemas must use local references");
				}
				JsonNode dialect = current.get("$schema");
				require(dialect == null || SpecificationVersion.DRAFT_2020_12.getDialectId().equals(dialect.asString()),
						"Capability schemas must use Draft 2020-12");
			}
			// Traverse schema positions, not literal const/default/examples data or
			// property-name dictionaries, where a "$ref" key is not a reference.
			for (String keyword : List.of("$defs", "definitions", "properties", "patternProperties", "dependentSchemas",
					"dependencies", "allOf", "anyOf", "oneOf", "prefixItems")) {
				JsonNode children = current.get(keyword);
				if (children != null && children.isContainer()) {
					children.forEach(pending::add);
				}
			}
			for (String keyword : List.of("items", "additionalItems", "additionalProperties", "unevaluatedProperties",
					"unevaluatedItems", "contains", "propertyNames", "not", "if", "then", "else", "contentSchema")) {
				JsonNode child = current.get(keyword);
				if (child != null) {
					pending.add(child);
				}
			}
		}
		try {
			PlanCodec.SCHEMAS.getSchema(node).initializeValidators();
		}
		catch (RuntimeException ex) {
			throw new PlanValidationException("Cannot resolve capability schema", ex);
		}
		return node;
	}

	static void text(String value, String name) {
		require(value != null && !value.isBlank(), name + " must not be blank");
	}

	static void require(boolean condition, String message) {
		if (!condition) {
			throw new PlanValidationException(message);
		}
	}

}
