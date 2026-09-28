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
package io.github.agentic.spring.ai.graph.agent.flow.agent;

import io.github.agentic.spring.ai.graph.StateGraph;
import io.github.agentic.spring.ai.graph.agent.flow.builder.FlowAgentBuilder;
import io.github.agentic.spring.ai.graph.agent.flow.builder.FlowGraphBuilder;
import io.github.agentic.spring.ai.graph.agent.flow.enums.FlowAgentEnum;
import io.github.agentic.spring.ai.graph.exception.GraphStateException;

public class SequentialAgent extends FlowAgent {

	protected SequentialAgent(SequentialAgentBuilder builder) {
		super(builder.name, builder.description, builder.compileConfig, builder.subAgents, builder.stateSerializer, builder.executor, builder.hooks);
	}

	public static SequentialAgentBuilder builder() {
		return new SequentialAgentBuilder();
	}

	@Override
	protected StateGraph buildSpecificGraph(FlowGraphBuilder.FlowGraphConfig config) throws GraphStateException {
		return FlowGraphBuilder.buildGraph(FlowAgentEnum.SEQUENTIAL.getType(), config);
	}

	/**
	 * Builder for creating SequentialAgent instances. Extends the common FlowAgentBuilder
	 * to provide type-safe building.
	 */
	public static class SequentialAgentBuilder extends FlowAgentBuilder<SequentialAgent, SequentialAgentBuilder> {

		@Override
		protected SequentialAgentBuilder self() {
			return this;
		}

		@Override
		protected void validate() {
			super.validate();
			// Add any SequentialAgent-specific validation here if needed
		}

		@Override
		public SequentialAgent doBuild() {
			validate();
			return new SequentialAgent(this);
		}

	}

}
