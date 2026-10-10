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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-free tests for {@link EffectAwareToolRetryInterceptor}: the retry loop is driven
 * with a lambda {@link ToolCallHandler}, a fake clock and a recording sleeper, so each
 * case pins one row of the decision matrix from the T04 acceptance criteria.
 */
class EffectAwareToolRetryInterceptorTest {

	private static final String TOOL = "transfer_money";

	private final List<Long> sleeps = new ArrayList<>();

	private final AtomicLong nanos = new AtomicLong();

	private ToolCallRequest request() {
		return new ToolCallRequest(TOOL, "{\"amount\":1}", "call-1", new HashMap<>());
	}

	private EffectAwareToolRetryInterceptor.Builder builder() {
		return EffectAwareToolRetryInterceptor.builder()
			.maxAttempts(3)
			.initialDelay(100)
			.backoffFactor(2.0)
			.jitter(false)
			.nanoTime(this.nanos::get)
			.sleeper(delay -> {
				this.sleeps.add(delay);
				this.nanos.addAndGet(Duration.ofMillis(delay).toNanos());
			});
	}

	private static ToolCallResponse timeoutResponse(ToolCallRequest request) {
		return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), "timed out",
				Map.of(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_TIMEOUT,
						ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, "java.util.concurrent.TimeoutException"));
	}

	private static ToolCallResponse executionResponse(ToolCallRequest request, Class<? extends Throwable> type) {
		return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), type.getSimpleName(),
				Map.of(ToolCallResponse.FAILURE_KIND_METADATA_KEY, ToolCallResponse.FAILURE_KIND_EXECUTION,
						ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, type.getName()));
	}

	private static ToolCallHandler alwaysFailing(AtomicInteger calls,
			java.util.function.Function<ToolCallRequest, ToolCallResponse> failure) {
		return r -> {
			calls.incrementAndGet();
			return failure.apply(r);
		};
	}

	// --- acceptance: known permanent failures stop after one attempt ---

	@Test
	void permanentFailureIsNotRetriedEvenForReadOnlyTool() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.effects(ToolEffectRegistry.builder().readOnly(TOOL).build())
			.build();

		ToolCallResponse response = interceptor.interceptToolCall(request(),
				alwaysFailing(calls, r -> executionResponse(r, IllegalArgumentException.class)));

		assertEquals(1, calls.get());
		assertTrue(response.isError());
		assertEquals("permanent", response.getMetadata().get(EffectAwareToolRetryInterceptor.FAILURE_CATEGORY_METADATA_KEY));
		assertEquals(false, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
		assertEquals(1, response.getMetadata().get(EffectAwareToolRetryInterceptor.ATTEMPTS_METADATA_KEY));
		assertTrue(this.sleeps.isEmpty());
	}

	@Test
	void unresolvedToolIsPermanent() {
		AtomicInteger calls = new AtomicInteger();
		ToolCallResponse response = builder().build()
			.interceptToolCall(request(),
					alwaysFailing(calls,
							r -> ToolCallResponse.error(r.getToolCallId(), r.getToolName(), "no such tool",
									Map.of(ToolCallResponse.FAILURE_KIND_METADATA_KEY,
											ToolCallResponse.FAILURE_KIND_UNRESOLVED))));
		assertEquals(1, calls.get());
		assertEquals("permanent", response.getMetadata().get(EffectAwareToolRetryInterceptor.FAILURE_CATEGORY_METADATA_KEY));
	}

	// --- acceptance: pre-dispatch transient failures retry up to the attempt budget ---

	@Test
	void preDispatchFailureIsRetriedUpToMaxAttemptsRegardlessOfEffect() {
		AtomicInteger calls = new AtomicInteger();
		// No declaration at all: the tool is UNSAFE, but nothing reached the backend.
		ToolCallResponse response = builder().build()
			.interceptToolCall(request(), alwaysFailing(calls, r -> executionResponse(r, ConnectException.class)));

		assertEquals(3, calls.get());
		assertEquals(List.of(100L, 200L), this.sleeps);
		assertTrue(response.isError());
		assertEquals("pre_dispatch", response.getMetadata().get(EffectAwareToolRetryInterceptor.FAILURE_CATEGORY_METADATA_KEY));
		assertEquals("unsafe", response.getMetadata().get(EffectAwareToolRetryInterceptor.EFFECT_METADATA_KEY));
		assertEquals(false, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
		assertEquals(3, response.getMetadata().get(EffectAwareToolRetryInterceptor.ATTEMPTS_METADATA_KEY));
	}

	@Test
	void thrownPreDispatchExceptionIsRetriedAndSucceeds() {
		AtomicInteger calls = new AtomicInteger();
		ToolCallResponse response = builder().build().interceptToolCall(request(), r -> {
			if (calls.incrementAndGet() < 3) {
				throw new RuntimeException("wrapped", new ConnectException("refused"));
			}
			return ToolCallResponse.success(r.getToolCallId(), r.getToolName(), "ok");
		});
		assertEquals(3, calls.get());
		assertEquals(ToolCallResponse.SUCCESS_STATUS, response.getStatus());
		assertEquals("ok", response.getResult());
	}

	// --- acceptance: an unknown write tool is never redispatched after a possible send ---

	@Test
	void unsafeToolIsNotRedispatchedAfterTimeout() {
		AtomicInteger calls = new AtomicInteger();
		ToolCallResponse response = builder().build()
			.interceptToolCall(request(), alwaysFailing(calls, EffectAwareToolRetryInterceptorTest::timeoutResponse));

		assertEquals(1, calls.get());
		assertTrue(this.sleeps.isEmpty());
		assertTrue(response.isError());
		assertEquals(true, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
		assertEquals("uncertain", response.getMetadata().get(EffectAwareToolRetryInterceptor.FAILURE_CATEGORY_METADATA_KEY));
		assertEquals("unsafe", response.getMetadata().get(EffectAwareToolRetryInterceptor.EFFECT_METADATA_KEY));
		assertEquals(ToolCallResponse.FAILURE_KIND_TIMEOUT, response.getFailureKind().orElse(null));
		assertTrue(response.getResult().contains("not safe to repeat"));
	}

	@Test
	void unsafeToolIsNotRedispatchedAfterUnknownException() {
		AtomicInteger calls = new AtomicInteger();
		ToolCallResponse response = builder().build().interceptToolCall(request(), r -> {
			calls.incrementAndGet();
			throw new IllegalStateException("connection reset by peer");
		});
		assertEquals(1, calls.get());
		assertEquals(true, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
		assertEquals(IllegalStateException.class.getName(), response.getExceptionType().orElse(null));
	}

	@Test
	void readOnlyToolIsRedispatchedAfterTimeout() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.effects(ToolEffectRegistry.builder().readOnly(TOOL).build())
			.build();
		ToolCallResponse response = interceptor.interceptToolCall(request(), r -> {
			if (calls.incrementAndGet() < 2) {
				return timeoutResponse(r);
			}
			return ToolCallResponse.success(r.getToolCallId(), r.getToolName(), "ok");
		});
		assertEquals(2, calls.get());
		assertEquals(ToolCallResponse.SUCCESS_STATUS, response.getStatus());
	}

	@Test
	void idempotentToolIsRedispatchedAfterTimeoutAndReportsNoDoubtWhenExhausted() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.effects(ToolEffectRegistry.builder().idempotent(TOOL).build())
			.build();
		ToolCallResponse response = interceptor.interceptToolCall(request(),
				alwaysFailing(calls, EffectAwareToolRetryInterceptorTest::timeoutResponse));
		assertEquals(3, calls.get());
		assertEquals("idempotent", response.getMetadata().get(EffectAwareToolRetryInterceptor.EFFECT_METADATA_KEY));
		assertEquals(false, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
	}

	// --- reconcilable tools ---

	@Test
	void reconcilableToolReturnsReconciledOutcomeWithoutRedispatch() {
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger reconciliations = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.effects(ToolEffectRegistry.builder().reconcilable(TOOL).build())
			.reconciler(failure -> {
				reconciliations.incrementAndGet();
				assertEquals(1, failure.attempt());
				assertSame(TOOL, failure.request().getToolName());
				return Optional.of(ToolCallResponse.success(failure.request().getToolCallId(), TOOL, "transfer tx-9 applied"));
			})
			.build();

		ToolCallResponse response = interceptor.interceptToolCall(request(),
				alwaysFailing(calls, EffectAwareToolRetryInterceptorTest::timeoutResponse));

		assertEquals(1, calls.get());
		assertEquals(1, reconciliations.get());
		assertEquals(ToolCallResponse.SUCCESS_STATUS, response.getStatus());
		assertEquals("transfer tx-9 applied", response.getResult());
		assertEquals(true, response.getMetadata().get(EffectAwareToolRetryInterceptor.RECONCILED_METADATA_KEY));
		assertEquals(1, response.getMetadata().get(EffectAwareToolRetryInterceptor.ATTEMPTS_METADATA_KEY));
	}

	@Test
	void reconcilableToolIsRedispatchedWhenReconcilerFindsNothing() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.effects(ToolEffectRegistry.builder().reconcilable(TOOL).build())
			.reconciler(failure -> Optional.empty())
			.build();
		ToolCallResponse response = interceptor.interceptToolCall(request(), r -> {
			if (calls.incrementAndGet() < 2) {
				return timeoutResponse(r);
			}
			return ToolCallResponse.success(r.getToolCallId(), r.getToolName(), "ok");
		});
		assertEquals(2, calls.get());
		assertEquals(ToolCallResponse.SUCCESS_STATUS, response.getStatus());
	}

	@Test
	void reconcilableToolWithoutReconcilerIsTreatedAsUnsafe() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.effects(ToolEffectRegistry.builder().reconcilable(TOOL).build())
			.build();
		ToolCallResponse response = interceptor.interceptToolCall(request(),
				alwaysFailing(calls, EffectAwareToolRetryInterceptorTest::timeoutResponse));
		assertEquals(1, calls.get());
		assertEquals(true, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
	}

	// --- acceptance: the declaration comes from the registry, never from arguments ---

	@Test
	void effectDeclaredInArgumentsIsIgnored() {
		AtomicInteger calls = new AtomicInteger();
		Map<String, Object> context = new HashMap<>();
		context.put("toolEffect", "read_only");
		ToolCallRequest request = new ToolCallRequest(TOOL, "{\"idempotent\":true,\"toolEffect\":\"read_only\"}",
				"call-1", context);

		ToolCallResponse response = builder().build()
			.interceptToolCall(request, alwaysFailing(calls, EffectAwareToolRetryInterceptorTest::timeoutResponse));

		assertEquals(1, calls.get());
		assertEquals("unsafe", response.getMetadata().get(EffectAwareToolRetryInterceptor.EFFECT_METADATA_KEY));
		assertEquals(true, response.getMetadata().get(EffectAwareToolRetryInterceptor.IN_DOUBT_METADATA_KEY));
	}

	// --- deadline, tool filter, raise ---

	@Test
	void deadlineStopsRetryingBeforeTheBudgetIsExceeded() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder().maxAttempts(5)
			.deadline(Duration.ofMillis(250))
			.effects(ToolEffectRegistry.builder().readOnly(TOOL).build())
			.build();
		ToolCallResponse response = interceptor.interceptToolCall(request(),
				alwaysFailing(calls, EffectAwareToolRetryInterceptorTest::timeoutResponse));
		// delays are 100 and 200ms: the second retry would end at 300ms, past the 250ms deadline
		assertEquals(2, calls.get());
		assertEquals(List.of(100L), this.sleeps);
		assertEquals(2, response.getMetadata().get(EffectAwareToolRetryInterceptor.ATTEMPTS_METADATA_KEY));
	}

	@Test
	void toolsOutsideTheFilterPassThroughUntouched() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder().toolName("other_tool").build();
		ToolCallResponse response = interceptor.interceptToolCall(request(),
				alwaysFailing(calls, r -> executionResponse(r, ConnectException.class)));
		assertEquals(1, calls.get());
		assertFalse(response.getMetadata().containsKey(EffectAwareToolRetryInterceptor.EFFECT_METADATA_KEY));
	}

	@Test
	void raiseBehaviourThrowsWithTheLastExceptionAsCause() {
		AtomicInteger calls = new AtomicInteger();
		EffectAwareToolRetryInterceptor interceptor = builder()
			.onFailure(EffectAwareToolRetryInterceptor.OnFailureBehavior.RAISE)
			.build();
		SocketTimeoutException cause = new SocketTimeoutException("read timed out");
		RuntimeException ex = assertThrows(RuntimeException.class,
				() -> interceptor.interceptToolCall(request(), r -> {
					calls.incrementAndGet();
					throw new RuntimeException(cause);
				}));
		assertEquals(1, calls.get());
		assertTrue(ex.getMessage().contains("not safe to repeat"));
		assertSame(cause, ex.getCause().getCause());
	}

	@Test
	void successOnFirstAttemptIsReturnedAsIs() {
		ToolCallResponse ok = ToolCallResponse.success("call-1", TOOL, "ok");
		ToolCallResponse response = builder().build().interceptToolCall(request(), r -> ok);
		assertSame(ok, response);
		assertTrue(this.sleeps.isEmpty());
	}

}
