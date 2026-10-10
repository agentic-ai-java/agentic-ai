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
package io.github.agentic.ai.graph.agent.interceptor.toolretry;

/**
 * What a failed tool call means for a retry decision. The category answers one question:
 * could the tool's effect already have been applied?
 *
 * This is distinct from the mechanical
 * {@link io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse#getFailureKind()
 * failure kind} (timeout, cancelled, execution, unresolved) that the tool node records on
 * an error response; a {@link ToolFailureClassifier} maps the latter onto a category.
 */
public enum ToolFailureCategory {

	/**
	 * The failure happened before the request reached the tool backend (connection refused,
	 * unknown host, tool not resolved to a callable). Nothing was applied, so the call can
	 * be repeated regardless of the tool's effect.
	 */
	PRE_DISPATCH,

	/**
	 * The backend answered and rejected the call for a reason that will not change on a
	 * repeat (validation, authorization, business rule). Retrying is pointless.
	 */
	PERMANENT,

	/**
	 * The request may have been sent and the result was lost (timeout, cancellation,
	 * connection reset mid-flight, or an unknown error). Whether the effect was applied is
	 * unknown, so repeating it is only safe for tools whose {@link ToolEffect} allows it.
	 */
	UNCERTAIN

}
