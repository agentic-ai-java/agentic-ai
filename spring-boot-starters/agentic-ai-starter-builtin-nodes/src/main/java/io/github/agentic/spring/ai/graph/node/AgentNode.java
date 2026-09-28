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

package io.github.agentic.spring.ai.graph.node;

import io.github.agentic.spring.ai.graph.OverAllState;
import io.github.agentic.spring.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.lang.Nullable;
import org.springframework.core.retry.RetryPolicy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A graph node that delegates one turn of work to a {@link ChatClient}, optionally with
 * tools attached.
 *
 * <p>
 * The node renders {@code systemPrompt} and {@code userPrompt} as {@link PromptTemplate}s
 * against the current {@link OverAllState}, performs a single blocking model call
 * (retried up to {@code maxRetries} extra times), and writes the returned content to
 * {@code outputKey}.
 *
 * <p>
 * <strong>The node does not implement an agent loop itself.</strong> Any multi-step tool
 * calling is performed by Spring AI's own tool-calling loop inside
 * {@code ChatClient.call()}. See {@link Strategy} for what the two strategy values
 * actually change.
 *
 * <p>
 * <strong>Error handling.</strong> By default a model call that keeps failing is
 * swallowed and the node writes an empty string to {@code outputKey}. Set
 * {@code throwOnModelError(true)} to propagate the failure instead, so downstream nodes
 * can distinguish "the model returned nothing" from "the call failed".
 */
public class AgentNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(AgentNode.class);

	private final ChatClient chatClient;

	private final ToolCallback[] toolCallbacks;

	private final Strategy strategy;

	// Prompt can contain variables in the format {varName}, which will be replaced before
	// calling the Client
	private final String systemPrompt;

	private final String userPrompt;

	private final int maxRetries;

	private final boolean throwOnModelError;

	private final String outputKey;

	private final RetryTemplate retryTemplate;

	/**
	 * How tool results are handed back to the model.
	 *
	 * <p>
	 * Both values take the same code path in {@link #apply(OverAllState)}; they differ
	 * only in how tool callbacks are prepared at construction time.
	 */
	public enum Strategy {

		/**
		 * Tool results are fed back to the model, which decides whether to call another
		 * tool or produce the final answer. The loop is Spring AI's tool-calling loop,
		 * not one implemented by this node.
		 */
		REACT,

		/**
		 * Every tool callback is wrapped so that {@code ToolMetadata.returnDirect()} is
		 * {@code true}: the first tool result is returned to the caller as-is, without a
		 * follow-up model call.
		 */
		TOOL_CALLING

	}

	/**
	 * @deprecated since 2.1.0 in favour of
	 * {@link #AgentNode(ChatClient, ToolCallback[], Strategy, String, String, Integer, String, boolean)}.
	 * The {@code maxIterations} parameter never controlled agent iterations; it is the
	 * retry count. See {@link Builder#maxRetries(Integer)}.
	 */
	@Deprecated(since = "2.1.0", forRemoval = false)
	public AgentNode(ChatClient chatClient, ToolCallback[] toolCallbacks, Strategy strategy, String systemPrompt,
			String userPrompt, Integer maxIterations, String outputKey) {
		this(chatClient, toolCallbacks, strategy, systemPrompt, userPrompt, maxIterations, outputKey, false);
	}

	/**
	 * @param maxRetries how many times the model call is retried <em>after</em> the first
	 * attempt; {@code n} means up to {@code n + 1} model invocations. Defaults to
	 * {@code 1}, i.e. two invocations.
	 * @param throwOnModelError whether to propagate a model failure instead of writing an
	 * empty string to {@code outputKey}
	 */
	public AgentNode(ChatClient chatClient, ToolCallback[] toolCallbacks, Strategy strategy, String systemPrompt,
			String userPrompt, Integer maxRetries, String outputKey, boolean throwOnModelError) {
		this.chatClient = chatClient;
		this.strategy = strategy == null ? Strategy.REACT : strategy;
		this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
		this.userPrompt = userPrompt == null ? "" : userPrompt;
		this.maxRetries = maxRetries == null ? 1 : maxRetries;
		this.throwOnModelError = throwOnModelError;
		this.outputKey = outputKey == null ? "agent_output" : outputKey;
		if (this.chatClient == null) {
			throw new IllegalArgumentException("ChatClient is required");
		}
		if (this.maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries must not be negative");
		}
		if (this.systemPrompt.isBlank() && this.userPrompt.isBlank()) {
			throw new IllegalArgumentException("At least one of systemPrompt or userPrompt is required");
		}

		// Initialize retryTemplate
		this.retryTemplate = new RetryTemplate();
		RetryPolicy retryPolicy = RetryPolicy.builder().maxRetries(this.maxRetries).build();
		this.retryTemplate.setRetryPolicy(retryPolicy);

		// Initialize toolCallbacks
		ToolCallback[] sourceCallbacks = toolCallbacks == null ? new ToolCallback[0] : toolCallbacks;
		this.toolCallbacks = Arrays.stream(sourceCallbacks).map(toolCallback -> {
			// ToolCalling strategy returns directly after calling the tool, needs to be
			// wrapped to set returnDirect to true
			if (this.strategy == Strategy.TOOL_CALLING && !toolCallback.getToolMetadata().returnDirect()) {
				final ToolMetadata toolMetadata = ToolMetadata.builder().returnDirect(true).build();
				return new ToolCallback() {
					@Override
					public ToolDefinition getToolDefinition() {
						return toolCallback.getToolDefinition();
					}

					@Override
					public ToolMetadata getToolMetadata() {
						// ToolMetadata with returnDirect set to true
						return toolMetadata;
					}

					@Override
					public String call(String toolInput) {
						return toolCallback.call(toolInput);
					}

					@Override
					public String call(String toolInput, @Nullable ToolContext tooContext) {
						return toolCallback.call(toolInput, tooContext);
					}

				};
			}
			else {
				return toolCallback;
			}
		}).toArray(ToolCallback[]::new);
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String userPrompt = render(this.userPrompt, state);
		String systemPrompt = render(this.systemPrompt, state);
		String output = switch (this.strategy) {
			case TOOL_CALLING, REACT -> {
				// Retry mechanism
				try {
					yield this.retryTemplate.execute(() -> {
						String content = callModel(systemPrompt, userPrompt);
						if (content == null) {
							logger.warn("ChatClient Call Return Null...");
							throw new RuntimeException("ChatClient Call Return Null...");
						}
						return content;
					});
				}
				catch (Exception e) {
					if (this.throwOnModelError) {
						throw e;
					}
					logger.error(
							"Model call failed after {} attempt(s) (maxRetries={}); writing an empty result to '{}'",
							this.maxRetries + 1, this.maxRetries, this.outputKey, e);
					yield null;
				}
			}
		};
		return Map.of(this.outputKey, output == null ? "" : output);
	}

	private String callModel(String systemPrompt, String userPrompt) {
		// ChatClient rejects blank prompt text, so an unset prompt must be skipped
		// entirely
		ChatClient.ChatClientRequestSpec request = systemPrompt.isBlank() ? this.chatClient.prompt()
				: this.chatClient.prompt(systemPrompt);
		request = request.tools(this.toolCallbacks);
		if (!userPrompt.isBlank()) {
			request = request.user(userPrompt);
		}
		return request.call().content();
	}

	private static String render(String template, OverAllState state) {
		// PromptTemplate rejects an empty template, and an empty template has nothing to
		// resolve
		return template.isBlank() ? "" : new PromptTemplate(template).render(state.data());
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private ChatClient chatClient;

		private ToolCallback[] toolCallbacks;

		private Strategy strategy;

		private String systemPrompt;

		private String userPrompt;

		private Integer maxRetries;

		private boolean throwOnModelError;

		private String outputKey;

		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		public Builder toolCallbacks(ToolCallback[] toolCallbacks) {
			this.toolCallbacks = toolCallbacks;
			return this;
		}

		public Builder toolCallBacks(List<ToolCallback> toolCallbacks) {
			this.toolCallbacks = toolCallbacks.toArray(ToolCallback[]::new);
			return this;
		}

		public Builder strategy(Strategy strategy) {
			this.strategy = strategy;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder userPrompt(String userPrompt) {
			this.userPrompt = userPrompt;
			return this;
		}

		/**
		 * @deprecated since 2.1.0 in favour of {@link #maxRetries(Integer)}. The value
		 * never controlled agent iterations; it has always been the retry count.
		 */
		@Deprecated(since = "2.1.0", forRemoval = false)
		public Builder maxIterations(Integer maxIterations) {
			return maxRetries(maxIterations);
		}

		/**
		 * How many times the model call is retried <em>after</em> the first attempt.
		 * {@code n} means up to {@code n + 1} model invocations. Defaults to {@code 1},
		 * i.e. two invocations.
		 * @param maxRetries the retry count, not the total attempt count
		 * @return this builder
		 */
		public Builder maxRetries(Integer maxRetries) {
			this.maxRetries = maxRetries;
			return this;
		}

		/**
		 * Whether to propagate an exception raised by the model call once the retries are
		 * exhausted, instead of logging it and writing an empty string to
		 * {@code outputKey}. Defaults to {@code false} for backward compatibility.
		 * @param throwOnModelError whether to propagate model call failures
		 * @return this builder
		 */
		public Builder throwOnModelError(boolean throwOnModelError) {
			this.throwOnModelError = throwOnModelError;
			return this;
		}

		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		public AgentNode build() {
			return new AgentNode(chatClient, toolCallbacks, strategy, systemPrompt, userPrompt, maxRetries, outputKey,
					throwOnModelError);
		}

	}

}
