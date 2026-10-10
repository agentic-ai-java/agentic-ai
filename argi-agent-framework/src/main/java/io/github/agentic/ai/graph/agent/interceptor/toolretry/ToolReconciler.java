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

import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;

import java.util.Optional;

/**
 * Looks up whether the effect of a {@link ToolEffect#RECONCILABLE} tool was applied after
 * an {@link ToolFailureCategory#UNCERTAIN uncertain} failure, so the agent can continue
 * without dispatching the tool a second time.
 *
 * Typical implementations query the backend by the business key found in the tool
 * arguments (an order number, an idempotency key, a transfer reference).
 */
@FunctionalInterface
public interface ToolReconciler {

	/**
	 * Try to determine the outcome of the failed call.
	 * @param failure the failed attempt, including the original request
	 * @return the response the tool would have produced if the effect is found to be
	 * applied; empty if the effect is known not to have been applied, in which case the
	 * retry policy may dispatch the tool again
	 */
	Optional<ToolCallResponse> reconcile(ToolFailure failure);

}
