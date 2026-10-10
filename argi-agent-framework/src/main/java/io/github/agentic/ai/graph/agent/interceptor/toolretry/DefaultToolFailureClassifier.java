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

import io.github.agentic.ai.graph.agent.interceptor.ToolCallResponse;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link ToolFailureClassifier}. It looks at the structured failure metadata the
 * tool node records on error responses first, and at the exception type second:
 *
 * <ul>
 * <li>{@code timeout} and {@code cancelled} failures are {@link ToolFailureCategory#UNCERTAIN}:
 * the request may already have been sent.</li>
 * <li>{@code unresolved} failures (no callable tool for the name) are
 * {@link ToolFailureCategory#PERMANENT}: nothing was dispatched, but a repeat cannot
 * succeed either.</li>
 * <li>{@code execution} failures and exceptions thrown by the handler are placed by their
 * exception type: connection-level failures that happen before a request is sent are
 * {@link ToolFailureCategory#PRE_DISPATCH}, argument and authorization failures are
 * {@link ToolFailureCategory#PERMANENT}, everything else is
 * {@link ToolFailureCategory#UNCERTAIN}.</li>
 * </ul>
 *
 * Exception types are matched by fully qualified class name against the exception, its
 * superclasses and its causes, so the same rule applies to a type recorded in response
 * metadata and to a thrown exception. Additional types can be registered through the
 * {@link Builder}.
 */
public final class DefaultToolFailureClassifier implements ToolFailureClassifier {

	private static final Set<String> DEFAULT_PRE_DISPATCH_TYPES = Set.of("java.net.ConnectException",
			"java.net.UnknownHostException", "java.net.NoRouteToHostException",
			"java.net.http.HttpConnectTimeoutException", "java.nio.channels.UnresolvedAddressException");

	private static final Set<String> DEFAULT_PERMANENT_TYPES = Set.of("java.lang.IllegalArgumentException",
			"java.lang.SecurityException", "java.lang.UnsupportedOperationException");

	private final Set<String> preDispatchTypes;

	private final Set<String> permanentTypes;

	private final Set<String> uncertainTypes;

	private DefaultToolFailureClassifier(Builder builder) {
		this.preDispatchTypes = Collections.unmodifiableSet(new HashSet<>(builder.preDispatchTypes));
		this.permanentTypes = Collections.unmodifiableSet(new HashSet<>(builder.permanentTypes));
		this.uncertainTypes = Collections.unmodifiableSet(new HashSet<>(builder.uncertainTypes));
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ToolFailureCategory classify(ToolFailure failure) {
		Optional<String> failureKind = failure.failureKind();
		if (failureKind.isPresent()) {
			switch (failureKind.get()) {
				case ToolCallResponse.FAILURE_KIND_TIMEOUT:
				case ToolCallResponse.FAILURE_KIND_CANCELLED:
					return ToolFailureCategory.UNCERTAIN;
				case ToolCallResponse.FAILURE_KIND_UNRESOLVED:
					return ToolFailureCategory.PERMANENT;
				default:
					break;
			}
		}
		if (failure.exception().isPresent()) {
			return classifyException(failure.exception().get());
		}
		return failure.exceptionType().flatMap(this::classifyTypeName).orElse(ToolFailureCategory.UNCERTAIN);
	}

	private ToolFailureCategory classifyException(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			Class<?> type = current.getClass();
			while (type != null && type != Object.class) {
				Optional<ToolFailureCategory> category = classifyTypeName(type.getName());
				if (category.isPresent()) {
					return category.get();
				}
				type = type.getSuperclass();
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return ToolFailureCategory.UNCERTAIN;
	}

	private Optional<ToolFailureCategory> classifyTypeName(String typeName) {
		if (this.permanentTypes.contains(typeName)) {
			return Optional.of(ToolFailureCategory.PERMANENT);
		}
		if (this.preDispatchTypes.contains(typeName)) {
			return Optional.of(ToolFailureCategory.PRE_DISPATCH);
		}
		if (this.uncertainTypes.contains(typeName)) {
			return Optional.of(ToolFailureCategory.UNCERTAIN);
		}
		return Optional.empty();
	}

	public static final class Builder {

		private final Set<String> preDispatchTypes = new HashSet<>(DEFAULT_PRE_DISPATCH_TYPES);

		private final Set<String> permanentTypes = new HashSet<>(DEFAULT_PERMANENT_TYPES);

		private final Set<String> uncertainTypes = new HashSet<>();

		private Builder() {
		}

		/**
		 * Treat the given exception types (and their subclasses and causes) as failures
		 * that happen before the request is sent.
		 * @param types exception classes
		 * @return this builder
		 */
		@SafeVarargs
		public final Builder preDispatch(Class<? extends Throwable>... types) {
			return preDispatchTypes(names(types));
		}

		public Builder preDispatchTypes(String... typeNames) {
			return register(this.preDispatchTypes, typeNames);
		}

		/**
		 * Treat the given exception types (and their subclasses and causes) as permanent
		 * failures that a repeat cannot fix.
		 * @param types exception classes
		 * @return this builder
		 */
		@SafeVarargs
		public final Builder permanent(Class<? extends Throwable>... types) {
			return permanentTypes(names(types));
		}

		public Builder permanentTypes(String... typeNames) {
			return register(this.permanentTypes, typeNames);
		}

		/**
		 * Treat the given exception types as uncertain even if a superclass is registered
		 * as pre-dispatch or permanent.
		 * @param types exception classes
		 * @return this builder
		 */
		@SafeVarargs
		public final Builder uncertain(Class<? extends Throwable>... types) {
			return uncertainTypes(names(types));
		}

		public Builder uncertainTypes(String... typeNames) {
			return register(this.uncertainTypes, typeNames);
		}

		private Builder register(Set<String> target, String... typeNames) {
			for (String typeName : typeNames) {
				if (typeName == null || typeName.isBlank()) {
					throw new IllegalArgumentException("exception type name must not be blank");
				}
				this.preDispatchTypes.remove(typeName);
				this.permanentTypes.remove(typeName);
				this.uncertainTypes.remove(typeName);
				target.add(typeName);
			}
			return this;
		}

		@SafeVarargs
		private static String[] names(Class<? extends Throwable>... types) {
			String[] names = new String[types.length];
			for (int i = 0; i < types.length; i++) {
				names[i] = types[i].getName();
			}
			return names;
		}

		public DefaultToolFailureClassifier build() {
			return new DefaultToolFailureClassifier(this);
		}

	}

}
