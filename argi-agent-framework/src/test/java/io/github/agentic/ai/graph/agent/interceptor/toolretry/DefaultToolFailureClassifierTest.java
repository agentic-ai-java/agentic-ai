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

import io.github.agentic.ai.graph.agent.interceptor.ToolCallRequest;
import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultToolFailureClassifierTest {

	private final ToolFailureClassifier classifier = ToolFailureClassifier.defaults();

	private final ToolCallRequest request = new ToolCallRequest("t", "{}", "id-1", new HashMap<>());

	private ToolFailure response(String failureKind, String exceptionType) {
		Map<String, Object> metadata = new HashMap<>();
		if (failureKind != null) {
			metadata.put(ToolCallResponse.FAILURE_KIND_METADATA_KEY, failureKind);
		}
		if (exceptionType != null) {
			metadata.put(ToolCallResponse.EXCEPTION_TYPE_METADATA_KEY, exceptionType);
		}
		return ToolFailure.ofResponse(this.request, ToolCallResponse.error("id-1", "t", "failed", metadata), 1);
	}

	private ToolFailure exception(Exception ex) {
		return ToolFailure.ofException(this.request, ex, 1);
	}

	@Test
	void timeoutAndCancellationAreUncertain() {
		assertEquals(ToolFailureCategory.UNCERTAIN,
				this.classifier.classify(response(ToolCallResponse.FAILURE_KIND_TIMEOUT, null)));
		assertEquals(ToolFailureCategory.UNCERTAIN,
				this.classifier.classify(response(ToolCallResponse.FAILURE_KIND_CANCELLED, null)));
	}

	@Test
	void unresolvedToolIsPermanent() {
		assertEquals(ToolFailureCategory.PERMANENT,
				this.classifier.classify(response(ToolCallResponse.FAILURE_KIND_UNRESOLVED, null)));
	}

	@Test
	void executionFailureIsPlacedByRecordedExceptionType() {
		assertEquals(ToolFailureCategory.PRE_DISPATCH, this.classifier
			.classify(response(ToolCallResponse.FAILURE_KIND_EXECUTION, ConnectException.class.getName())));
		assertEquals(ToolFailureCategory.PERMANENT, this.classifier
			.classify(response(ToolCallResponse.FAILURE_KIND_EXECUTION, IllegalArgumentException.class.getName())));
		assertEquals(ToolFailureCategory.UNCERTAIN, this.classifier
			.classify(response(ToolCallResponse.FAILURE_KIND_EXECUTION, SocketTimeoutException.class.getName())));
		assertEquals(ToolFailureCategory.UNCERTAIN,
				this.classifier.classify(response(ToolCallResponse.FAILURE_KIND_EXECUTION, "com.acme.OddError")));
	}

	@Test
	void responseWithoutMetadataIsUncertain() {
		assertEquals(ToolFailureCategory.UNCERTAIN, this.classifier.classify(response(null, null)));
	}

	@Test
	void thrownExceptionsAreMatchedThroughSuperclassesAndCauses() {
		assertEquals(ToolFailureCategory.PRE_DISPATCH, this.classifier.classify(exception(new UnknownHostException("x"))));
		assertEquals(ToolFailureCategory.PRE_DISPATCH,
				this.classifier.classify(exception(new RuntimeException("wrapped", new ConnectException("refused")))));
		assertEquals(ToolFailureCategory.PERMANENT,
				this.classifier.classify(exception(new NumberFormatException("not a number"))));
		assertEquals(ToolFailureCategory.UNCERTAIN, this.classifier.classify(exception(new IOException("reset"))));
		assertEquals(ToolFailureCategory.UNCERTAIN, this.classifier.classify(exception(new TimeoutException())));
	}

	@Test
	void customRulesOverrideDefaults() {
		ToolFailureClassifier custom = DefaultToolFailureClassifier.builder()
			.permanent(IOException.class)
			.uncertain(ConnectException.class)
			.preDispatchTypes("com.acme.BackendUnavailable")
			.build();
		assertEquals(ToolFailureCategory.PERMANENT, custom.classify(exception(new IOException("x"))));
		// the more specific uncertain rule wins over the inherited permanent IOException rule
		assertEquals(ToolFailureCategory.UNCERTAIN, custom.classify(exception(new ConnectException("x"))));
		assertEquals(ToolFailureCategory.PRE_DISPATCH,
				custom.classify(response(ToolCallResponse.FAILURE_KIND_EXECUTION, "com.acme.BackendUnavailable")));
	}

}
