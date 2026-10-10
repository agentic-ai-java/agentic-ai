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

import java.util.Optional;

/**
 * One failed attempt of a tool call, as seen by a {@link ToolFailureClassifier} or a
 * {@link ToolReconciler}. Exactly one of {@link #response()} and {@link #exception()} is
 * present: the tool node normally converts failures into an error
 * {@link ToolCallResponse}, while an exception is what a downstream interceptor or a
 * handler threw directly.
 */
public final class ToolFailure {

	private final ToolCallRequest request;

	private final ToolCallResponse response;

	private final Exception exception;

	private final int attempt;

	ToolFailure(ToolCallRequest request, ToolCallResponse response, Exception exception, int attempt) {
		this.request = request;
		this.response = response;
		this.exception = exception;
		this.attempt = attempt;
	}

	public static ToolFailure ofResponse(ToolCallRequest request, ToolCallResponse response, int attempt) {
		return new ToolFailure(request, response, null, attempt);
	}

	public static ToolFailure ofException(ToolCallRequest request, Exception exception, int attempt) {
		return new ToolFailure(request, null, exception, attempt);
	}

	public ToolCallRequest request() {
		return this.request;
	}

	/**
	 * The error response returned by the handler, if the failure was reported as a
	 * response.
	 * @return the response, or empty when the handler threw instead
	 */
	public Optional<ToolCallResponse> response() {
		return Optional.ofNullable(this.response);
	}

	/**
	 * The exception thrown by the handler, if the failure was reported as an exception.
	 * @return the exception, or empty when the handler returned an error response
	 */
	public Optional<Exception> exception() {
		return Optional.ofNullable(this.exception);
	}

	/**
	 * The 1-based number of the attempt that failed.
	 * @return the attempt number
	 */
	public int attempt() {
		return this.attempt;
	}

	/**
	 * The mechanical failure kind recorded by the tool node on the error response, see
	 * {@link ToolCallResponse#getFailureKind()}.
	 * @return the failure kind, or empty for exceptions and responses without metadata
	 */
	public Optional<String> failureKind() {
		return response().flatMap(ToolCallResponse::getFailureKind);
	}

	/**
	 * The fully qualified exception type behind the failure: the
	 * {@link ToolCallResponse#getExceptionType() recorded type} for a response, or the
	 * class of the thrown exception.
	 * @return the exception type, or empty when unknown
	 */
	public Optional<String> exceptionType() {
		if (this.exception != null) {
			return Optional.of(this.exception.getClass().getName());
		}
		return response().flatMap(ToolCallResponse::getExceptionType);
	}

	/**
	 * A human readable description of the failure for log and error messages.
	 * @return the error message of the response or exception
	 */
	public String message() {
		if (this.exception != null) {
			return this.exception.getMessage() != null ? this.exception.getMessage()
					: this.exception.getClass().getSimpleName();
		}
		Object errorMessage = this.response.getMetadata().get("errorMessage");
		if (errorMessage != null) {
			return errorMessage.toString();
		}
		return this.response.getResult() != null ? this.response.getResult() : "unknown error";
	}

}
