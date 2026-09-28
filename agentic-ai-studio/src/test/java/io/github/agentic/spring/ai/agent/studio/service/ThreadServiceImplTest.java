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
package io.github.agentic.spring.ai.agent.studio.service;

import io.github.agentic.spring.ai.agent.studio.dto.Thread;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadServiceImplTest {

	@Test
	void createThreadReturnsValuesWithMessagesFromInitialState() {
		ThreadServiceImpl service = new ThreadServiceImpl();
		Map<String, Object> firstMessage = Map.of("messageType", "user", "content", "hello");

		Thread thread = service.createThread("assistant", "alice",
				Map.of("messages", List.of(firstMessage), "topic", "demo"), "thread-1").block();

		assertThat(thread).isNotNull();
		Map<?, ?> values = thread.values();
		assertThat(values.get("messages")).isEqualTo(List.of(firstMessage));
		assertThat(values.get("topic")).isEqualTo("demo");
	}

	@Test
	void concurrentCreateThreadWithSameIdAllowsOnlyOneCreator() throws Exception {
		ThreadServiceImpl service = new ThreadServiceImpl();
		int workers = 32;
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		CountDownLatch ready = new CountDownLatch(workers);
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<Boolean>> tasks = new ArrayList<>();

		for (int i = 0; i < workers; i++) {
			tasks.add(() -> {
				ready.countDown();
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				try {
					service.createThread("assistant", "alice", Map.of(), "shared-thread").block();
					return true;
				}
				catch (RuntimeException ex) {
					return false;
				}
			});
		}

		List<Future<Boolean>> results = tasks.stream().map(executor::submit).toList();
		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		long created = results.stream().filter(future -> {
			try {
				return future.get();
			}
			catch (Exception ex) {
				throw new AssertionError(ex);
			}
		}).count();
		executor.shutdownNow();

		assertThat(created).isEqualTo(1);
	}

	@Test
	void threadKeysCannotCollideWhenAppOrUserContainsSeparators() {
		ThreadServiceImpl service = new ThreadServiceImpl();

		service.createThread("a:b", "c", Map.of("owner", "first"), "shared-thread").block();
		service.createThread("a", "b:c", Map.of("owner", "second"), "shared-thread").block();

		Thread first = service.getThread("a:b", "c", "shared-thread", java.util.Optional.empty())
				.block()
				.orElseThrow();
		Thread second = service.getThread("a", "b:c", "shared-thread", java.util.Optional.empty())
				.block()
				.orElseThrow();

		assertThat(first.values()).containsEntry("owner", "first");
		assertThat(second.values()).containsEntry("owner", "second");
	}

}
