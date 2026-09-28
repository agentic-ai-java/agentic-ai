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
package io.github.agentic.spring.ai.agent.studio.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StudioExecutionAccess {

	public static final String TOKEN_HEADER = "X-Agentic-Studio-Token";

	public static final String TOKEN_PROPERTY = "spring.ai.alibaba.agent.studio.execution.auth-token";

	private final String requiredToken;

	public StudioExecutionAccess(@Value("${" + TOKEN_PROPERTY + ":}") String requiredToken) {
		this.requiredToken = requiredToken;
	}

	public void assertAllowed(String presentedToken) {
		if (!StringUtils.hasText(requiredToken)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Studio execution auth token is not configured");
		}
		if (!StringUtils.hasText(presentedToken) || !tokenEquals(requiredToken, presentedToken)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Studio execution token");
		}
	}

	private boolean tokenEquals(String expected, String actual) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

}
