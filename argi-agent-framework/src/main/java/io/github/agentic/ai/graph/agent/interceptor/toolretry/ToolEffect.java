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
 * The side-effect class of a tool, as declared by the application or the tool
 * registration. It decides whether a call whose outcome is unknown (timeout, cancellation,
 * lost connection after the request may have been sent) can be dispatched again.
 *
 * The declaration is deliberately not read from the model-supplied tool arguments: a model
 * must not be able to mark a write tool as safe to retry.
 */
public enum ToolEffect {

	/**
	 * The tool never changes state. An uncertain outcome can always be retried.
	 */
	READ_ONLY,

	/**
	 * The tool changes state, but the backend makes repeated calls with the same arguments
	 * converge to the same result (for example an idempotency key or an upsert). An
	 * uncertain outcome can be retried.
	 */
	IDEMPOTENT,

	/**
	 * The tool changes state and repeating it may apply the effect twice, but the effect
	 * can be looked up afterwards. An uncertain outcome is handed to a
	 * {@link ToolReconciler} before anything is dispatched again.
	 */
	RECONCILABLE,

	/**
	 * Nothing is known about the tool, or it is known to be non-idempotent and not
	 * reconcilable. An uncertain outcome must never be dispatched again. This is the
	 * default for every tool without a declaration.
	 */
	UNSAFE;

	/**
	 * Whether an outcome that may already have been applied can be dispatched again
	 * without risking a duplicate effect.
	 * @return true for {@link #READ_ONLY} and {@link #IDEMPOTENT}
	 */
	public boolean isSafeToRedispatch() {
		return this == READ_ONLY || this == IDEMPOTENT;
	}

}
