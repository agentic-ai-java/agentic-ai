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

import io.github.agentic.ai.graph.agent.interceptor.ToolCallHandler;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;
import io.github.agentic.ai.graph.agent.interceptor.ToolInterceptor;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool interceptor that retries failed tool calls only when it is safe to do so.
 *
 * Every failed attempt is placed into a {@link ToolFailureCategory} by a
 * {@link ToolFailureClassifier}, and the decision then depends on the tool's declared
 * {@link ToolEffect}:
 *
 * <ul>
 * <li>{@link ToolFailureCategory#PERMANENT}: stop after the first attempt.</li>
 * <li>{@link ToolFailureCategory#PRE_DISPATCH}: retry up to the attempt and deadline
 * budget, whatever the effect, because nothing reached the backend.</li>
 * <li>{@link ToolFailureCategory#UNCERTAIN}: retry {@link ToolEffect#READ_ONLY} and
 * {@link ToolEffect#IDEMPOTENT} tools; ask the {@link ToolReconciler} for
 * {@link ToolEffect#RECONCILABLE} tools and only redispatch when it reports the effect as
 * not applied; never redispatch {@link ToolEffect#UNSAFE} tools and report the call as
 * in doubt instead.</li>
 * </ul>
 *
 * Tools without a declaration in the {@link ToolEffectRegistry} are
 * {@link ToolEffect#UNSAFE}. The declaration is never taken from the tool call arguments.
 *
 * The interceptor is opt-in and leaves {@link ToolRetryInterceptor} untouched.
 *
 * Example:
 * EffectAwareToolRetryInterceptor interceptor = EffectAwareToolRetryInterceptor.builder()
 *     .effects(ToolEffectRegistry.builder().readOnly("search").reconcilable("create_order").build())
 *     .reconciler(failure -> orderService.lookup(failure.request()))
 *     .maxAttempts(3)
 *     .deadline(Duration.ofSeconds(30))
 *     .build();
 */
public class EffectAwareToolRetryInterceptor extends ToolInterceptor {

	private static final Logger log = LoggerFactory.getLogger(EffectAwareToolRetryInterceptor.class);

	/**
	 * Metadata key holding the declared {@link ToolEffect} of the tool, in lower case.
	 */
	public static final String EFFECT_METADATA_KEY = "toolEffect";

	/**
	 * Metadata key holding the {@link ToolFailureCategory} of the last failure, in lower
	 * case.
	 */
	public static final String FAILURE_CATEGORY_METADATA_KEY = "failureCategory";

	/**
	 * Metadata key holding the number of attempts that were dispatched.
	 */
	public static final String ATTEMPTS_METADATA_KEY = "attempts";

	/**
	 * Metadata key set to {@code true} when the call was not redispatched because its
	 * effect may already have been applied and could not be reconciled.
	 */
	public static final String IN_DOUBT_METADATA_KEY = "inDoubt";

	/**
	 * Metadata key set to {@code true} on a response produced by the
	 * {@link ToolReconciler} instead of the tool itself.
	 */
	public static final String RECONCILED_METADATA_KEY = "reconciled";

	private final int maxAttempts;

	private final Duration deadline;

	private final Set<String> toolNames;

	private final ToolEffectRegistry effects;

	private final ToolFailureClassifier classifier;

	private final ToolReconciler reconciler;

	private final OnFailureBehavior onFailure;

	private final double backoffFactor;

	private final long initialDelayMs;

	private final long maxDelayMs;

	private final boolean jitter;

	private final LongSupplier nanoTime;

	private final LongConsumer sleeper;

	private EffectAwareToolRetryInterceptor(Builder builder) {
		this.maxAttempts = builder.maxAttempts;
		this.deadline = builder.deadline;
		this.toolNames = builder.toolNames != null ? new HashSet<>(builder.toolNames) : null;
		this.effects = builder.effects;
		this.classifier = builder.classifier;
		this.reconciler = builder.reconciler;
		this.onFailure = builder.onFailure;
		this.backoffFactor = builder.backoffFactor;
		this.initialDelayMs = builder.initialDelayMs;
		this.maxDelayMs = builder.maxDelayMs;
		this.jitter = builder.jitter;
		this.nanoTime = builder.nanoTime;
		this.sleeper = builder.sleeper;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		String toolName = request.getToolName();
		if (this.toolNames != null && !this.toolNames.contains(toolName)) {
			return handler.call(request);
		}

		ToolEffect effect = this.effects.effectOf(toolName);
		long startNanos = this.nanoTime.getAsLong();
		ToolFailure failure = null;
		ToolFailureCategory category = null;
		boolean inDoubt = false;

		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			Outcome outcome = attempt(request, handler, attempt);
			if (outcome.success != null) {
				return outcome.success;
			}
			failure = outcome.failure;

			category = this.classifier.classify(failure);
			Decision decision = decide(failure, category, effect);
			if (decision.reconciled != null) {
				return decision.reconciled;
			}
			inDoubt = decision.inDoubt;
			if (!decision.retry) {
				log.debug("Tool '{}' failed with {} ({}) on attempt {}, not retrying: {}", toolName, category, effect,
						attempt, failure.message());
				break;
			}
			if (attempt >= this.maxAttempts) {
				log.debug("Tool '{}' failed on the last attempt {}/{}: {}", toolName, attempt, this.maxAttempts,
						failure.message());
				break;
			}
			long delayMs = calculateDelay(attempt - 1);
			if (exceedsDeadline(startNanos, delayMs)) {
				log.debug("Tool '{}' failed on attempt {} and the retry deadline of {} would be exceeded", toolName,
						attempt, this.deadline);
				break;
			}
			log.warn("Tool '{}' failed with {} ({}) on attempt {}/{}, retrying in {}ms: {}", toolName, category,
					effect, attempt, this.maxAttempts, delayMs, failure.message());
			sleep(delayMs);
		}

		return fail(request, failure, category, effect, inDoubt);
	}

	private Outcome attempt(ToolCallRequest request, ToolCallHandler handler, int attempt) {
		ToolCallResponse response;
		try {
			response = handler.call(request);
		}
		catch (Exception ex) {
			return Outcome.failed(ToolFailure.ofException(request, ex, attempt));
		}
		if (response == null) {
			return Outcome.failed(ToolFailure.ofException(request,
					new IllegalStateException("tool handler returned null"), attempt));
		}
		if (ToolCallResponse.SUCCESS_STATUS.equals(response.getStatus())) {
			return Outcome.succeeded(response);
		}
		return Outcome.failed(ToolFailure.ofResponse(request, response, attempt));
	}

	private Decision decide(ToolFailure failure, ToolFailureCategory category, ToolEffect effect) {
		switch (category) {
			case PERMANENT:
				return Decision.stop(false);
			case PRE_DISPATCH:
				return Decision.retry();
			case UNCERTAIN:
			default:
				break;
		}
		switch (effect) {
			case READ_ONLY:
				return Decision.retry();
			case IDEMPOTENT:
				return Decision.retry();
			case RECONCILABLE:
				return reconcile(failure);
			case UNSAFE:
			default:
				return Decision.stop(true);
		}
	}

	private Decision reconcile(ToolFailure failure) {
		if (this.reconciler == null) {
			log.warn("Tool '{}' is declared reconcilable but no ToolReconciler is configured, treating it as unsafe",
					failure.request().getToolName());
			return Decision.stop(true);
		}
		Optional<ToolCallResponse> reconciled = this.reconciler.reconcile(failure);
		if (reconciled.isPresent()) {
			ToolCallResponse response = reconciled.get();
			Map<String, Object> metadata = new HashMap<>(response.getMetadata());
			metadata.put(RECONCILED_METADATA_KEY, true);
			metadata.put(ATTEMPTS_METADATA_KEY, failure.attempt());
			return Decision.reconciled(ToolCallResponse.builder()
				.content(response.getResult())
				.toolName(response.getToolName())
				.toolCallId(response.getToolCallId())
				.status(response.getStatus())
				.metadata(metadata)
				.build());
		}
		// The effect is known not to have been applied, so dispatching again is safe.
		return Decision.retry();
	}

	private ToolCallResponse fail(ToolCallRequest request, ToolFailure failure, ToolFailureCategory category,
			ToolEffect effect, boolean inDoubt) {
		String message = inDoubt
				? "Tool call outcome is unknown after " + failure.attempt() + " attempt(s) and the tool is not safe to repeat: "
						+ failure.message()
				: "Tool call failed after " + failure.attempt() + " attempt(s): " + failure.message();

		if (this.onFailure == OnFailureBehavior.RAISE) {
			throw new RuntimeException(message, failure.exception().orElse(null));
		}

		Map<String, Object> metadata = new HashMap<>();
		failure.response().ifPresent(response -> {
			response.getFailureKind().ifPresent(kind -> metadata.put(ToolCallResponse.FAILURE_KIND_METADATA_KEY, kind));
			response.getExceptionType()
				.ifPresent(type -> metadata.put(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, type));
		});
		failure.exception()
			.ifPresent(ex -> metadata.put(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, ex.getClass().getName()));
		metadata.put(EFFECT_METADATA_KEY, effect.name().toLowerCase());
		metadata.put(FAILURE_CATEGORY_METADATA_KEY, category.name().toLowerCase());
		metadata.put(ATTEMPTS_METADATA_KEY, failure.attempt());
		metadata.put(IN_DOUBT_METADATA_KEY, inDoubt);

		log.error("Tool '{}' failed after {} attempt(s) ({}, {}, inDoubt={}): {}", request.getToolName(),
				failure.attempt(), category, effect, inDoubt, failure.message());
		return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), message, metadata);
	}

	private boolean exceedsDeadline(long startNanos, long delayMs) {
		if (this.deadline == null) {
			return false;
		}
		long elapsedNanos = this.nanoTime.getAsLong() - startNanos;
		return elapsedNanos + Duration.ofMillis(delayMs).toNanos() > this.deadline.toNanos();
	}

	private long calculateDelay(int retryNumber) {
		long delay = (long) (this.initialDelayMs * Math.pow(this.backoffFactor, retryNumber));
		delay = Math.min(delay, this.maxDelayMs);
		if (this.jitter) {
			double jitterFactor = 0.75 + (Math.random() * 0.5);
			delay = (long) (delay * jitterFactor);
		}
		return delay;
	}

	private void sleep(long delayMs) {
		this.sleeper.accept(delayMs);
	}

	@Override
	public String getName() {
		return "EffectAwareToolRetry";
	}

	public enum OnFailureBehavior {

		RAISE, RETURN_MESSAGE

	}

	private static final class Outcome {

		private final ToolCallResponse success;

		private final ToolFailure failure;

		private Outcome(ToolCallResponse success, ToolFailure failure) {
			this.success = success;
			this.failure = failure;
		}

		static Outcome succeeded(ToolCallResponse response) {
			return new Outcome(response, null);
		}

		static Outcome failed(ToolFailure failure) {
			return new Outcome(null, failure);
		}

	}

	private static final class Decision {

		private final boolean retry;

		private final boolean inDoubt;

		private final ToolCallResponse reconciled;

		private Decision(boolean retry, boolean inDoubt, ToolCallResponse reconciled) {
			this.retry = retry;
			this.inDoubt = inDoubt;
			this.reconciled = reconciled;
		}

		static Decision retry() {
			return new Decision(true, false, null);
		}

		static Decision stop(boolean inDoubt) {
			return new Decision(false, inDoubt, null);
		}

		static Decision reconciled(ToolCallResponse response) {
			return new Decision(false, false, response);
		}

	}

	public static class Builder {

		private int maxAttempts = 3;

		private Duration deadline;

		private Set<String> toolNames;

		private ToolEffectRegistry effects = ToolEffectRegistry.empty();

		private ToolFailureClassifier classifier = ToolFailureClassifier.defaults();

		private ToolReconciler reconciler;

		private OnFailureBehavior onFailure = OnFailureBehavior.RETURN_MESSAGE;

		private double backoffFactor = 2.0;

		private long initialDelayMs = 1000;

		private long maxDelayMs = 60000;

		private boolean jitter = true;

		private LongSupplier nanoTime = System::nanoTime;

		private LongConsumer sleeper = Builder::sleepUninterruptibly;

		/**
		 * Set the maximum number of attempts, including the first call. Must be >= 1.
		 * @param maxAttempts total attempts (initial call plus retries)
		 */
		public Builder maxAttempts(int maxAttempts) {
			if (maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be >= 1");
			}
			this.maxAttempts = maxAttempts;
			return this;
		}

		/**
		 * Set the total time budget for all attempts of one tool call, measured from the
		 * first dispatch. A retry is skipped when its backoff delay would end after the
		 * deadline. No deadline is applied by default.
		 * @param deadline the budget, or null for none
		 */
		public Builder deadline(Duration deadline) {
			if (deadline != null && (deadline.isNegative() || deadline.isZero())) {
				throw new IllegalArgumentException("deadline must be positive");
			}
			this.deadline = deadline;
			return this;
		}

		public Builder toolNames(Set<String> toolNames) {
			this.toolNames = toolNames;
			return this;
		}

		public Builder toolName(String toolName) {
			if (this.toolNames == null) {
				this.toolNames = new HashSet<>();
			}
			this.toolNames.add(toolName);
			return this;
		}

		/**
		 * Set the declared effects of the tools. Tools missing from the registry are
		 * {@link ToolEffect#UNSAFE}.
		 * @param effects the registry
		 */
		public Builder effects(ToolEffectRegistry effects) {
			if (effects == null) {
				throw new IllegalArgumentException("effects must not be null");
			}
			this.effects = effects;
			return this;
		}

		public Builder classifier(ToolFailureClassifier classifier) {
			if (classifier == null) {
				throw new IllegalArgumentException("classifier must not be null");
			}
			this.classifier = classifier;
			return this;
		}

		/**
		 * Set the reconciler used for {@link ToolEffect#RECONCILABLE} tools. Without one,
		 * reconcilable tools are treated as {@link ToolEffect#UNSAFE} after an uncertain
		 * failure.
		 * @param reconciler the reconciler
		 */
		public Builder reconciler(ToolReconciler reconciler) {
			this.reconciler = reconciler;
			return this;
		}

		public Builder onFailure(OnFailureBehavior behavior) {
			this.onFailure = behavior;
			return this;
		}

		public Builder backoffFactor(double backoffFactor) {
			this.backoffFactor = backoffFactor;
			return this;
		}

		public Builder initialDelay(long initialDelayMs) {
			this.initialDelayMs = initialDelayMs;
			return this;
		}

		public Builder maxDelay(long maxDelayMs) {
			this.maxDelayMs = maxDelayMs;
			return this;
		}

		public Builder jitter(boolean jitter) {
			this.jitter = jitter;
			return this;
		}

		/**
		 * Replace the clock used for the deadline. Intended for tests.
		 */
		Builder nanoTime(LongSupplier nanoTime) {
			this.nanoTime = nanoTime;
			return this;
		}

		/**
		 * Replace the function that waits between attempts. Intended for tests.
		 */
		Builder sleeper(LongConsumer sleeper) {
			this.sleeper = sleeper;
			return this;
		}

		public EffectAwareToolRetryInterceptor build() {
			return new EffectAwareToolRetryInterceptor(this);
		}

		private static void sleepUninterruptibly(long delayMs) {
			try {
				Thread.sleep(delayMs);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Retry interrupted", ex);
			}
		}

	}

}
