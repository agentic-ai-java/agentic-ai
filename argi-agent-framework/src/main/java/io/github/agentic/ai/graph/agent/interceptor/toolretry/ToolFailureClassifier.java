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
 * Maps a failed tool call attempt onto a {@link ToolFailureCategory}.
 *
 * Implementations should be conservative: when a failure cannot be placed with
 * confidence, {@link ToolFailureCategory#UNCERTAIN} is the safe answer, because it lets
 * the tool's {@link ToolEffect} decide whether a repeat is allowed.
 */
@FunctionalInterface
public interface ToolFailureClassifier {

	/**
	 * Classify a failed attempt.
	 * @param failure the failed attempt
	 * @return the category, never null
	 */
	ToolFailureCategory classify(ToolFailure failure);

	/**
	 * The default classifier, see {@link DefaultToolFailureClassifier}.
	 * @return a classifier with the default rules
	 */
	static ToolFailureClassifier defaults() {
		return DefaultToolFailureClassifier.builder().build();
	}

}
