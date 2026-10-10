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
 * Optional capability a tool implementation can expose to declare its own
 * {@link ToolEffect}. {@link ToolEffectRegistry#fromTools(Iterable)} collects these
 * declarations from the registered tool callbacks, so the declaration travels with the tool
 * rather than with the agent configuration.
 */
public interface ToolEffectAware {

	/**
	 * The side-effect class of this tool.
	 * @return the declared effect, never null
	 */
	ToolEffect toolEffect();

}
