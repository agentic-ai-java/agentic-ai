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
package io.github.agentic.spring.ai.graph.agent.interceptor.modelretry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryDelayCalculatorTest {

	@Test
	void exponentialDelayIsCappedWithoutReadingTheClock() {
		assertEquals(150, RetryDelayCalculator.nextDelay(100, 3.0, 150));
		assertEquals(150, RetryDelayCalculator.nextDelay(150, 3.0, 150));
	}

	@Test
	void zeroDelayRemainsZero() {
		assertEquals(0, RetryDelayCalculator.nextDelay(0, 2.0, 30_000));
	}

}
