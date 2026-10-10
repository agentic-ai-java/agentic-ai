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
package io.github.agentic.ai.graph.agent.validation;

import io.github.agentic.ai.graph.agent.validation.ToolArgumentValidator.ToolArgumentValidationFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArgumentValidatorTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "name": { "type": "string" },
			    "level": { "type": "integer", "minimum": 1, "maximum": 5 },
			    "mode": { "enum": ["fast", "slow"] },
			    "tags": { "type": "array", "items": { "type": "string" } }
			  },
			  "required": ["name"],
			  "additionalProperties": false
			}""";

	@Test
	@DisplayName("rejects arguments that are not valid JSON")
	void invalidJsonIsRejected() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "{\"name\": ");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_JSON", failure.get().errorCode());
		assertEquals("$", failure.get().fieldPath());
	}

	@Test
	@DisplayName("rejects non-object arguments")
	void nonObjectArgumentsAreRejected() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "[1,2,3]");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
	}

	@Test
	@DisplayName("missing required property fails with its field path")
	void missingRequiredPropertyFails() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "{\"level\": 3}");
		assertTrue(failure.isPresent());
		assertEquals("REQUIRED_MISSING", failure.get().errorCode());
		assertEquals("$.name", failure.get().fieldPath());
	}

	@Test
	@DisplayName("type violation fails with the property path")
	void typeViolationFails() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": 42}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.name", failure.get().fieldPath());
	}

	@Test
	@DisplayName("integer type accepts integral numbers and rejects fractional ones")
	void integerTypeIsEnforced() {
		assertTrue(ToolArgumentValidator.validate(SCHEMA, "{\"name\": \"x\", \"level\": 5}").isEmpty());
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"level\": 2.5}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.level", failure.get().fieldPath());
	}

	@Test
	@DisplayName("enum violation fails without echoing the value")
	void enumViolationFails() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"mode\": \"medium\"}");
		assertTrue(failure.isPresent());
		assertEquals("ENUM_VIOLATION", failure.get().errorCode());
		assertFalse(failure.get().message().contains("medium"), "message must not echo argument values");
	}

	@Test
	@DisplayName("range violations fail below minimum and above maximum")
	void rangeViolationsFail() {
		Optional<ToolArgumentValidationFailure> below = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"level\": 0}");
		assertTrue(below.isPresent());
		assertEquals("RANGE_VIOLATION", below.get().errorCode());

		Optional<ToolArgumentValidationFailure> above = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"level\": 6}");
		assertTrue(above.isPresent());
		assertEquals("RANGE_VIOLATION", above.get().errorCode());
	}

	@Test
	@DisplayName("undeclared property fails when additionalProperties is false")
	void additionalPropertyFailsWhenProhibited() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"secret\": \"value\"}");
		assertTrue(failure.isPresent());
		assertEquals("ADDITIONAL_PROPERTY", failure.get().errorCode());
		assertEquals("$.secret", failure.get().fieldPath());
		assertFalse(failure.get().message().contains("value"), "message must not echo argument values");
	}

	@Test
	@DisplayName("null is rejected for a typed property but accepted for nullable types")
	void nullHandlingFollowsDeclaredTypes() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": null}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());

		String nullableSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":[\"string\",\"null\"]}}}";
		assertTrue(ToolArgumentValidator.validate(nullableSchema, "{\"name\": null}").isEmpty());
	}

	@Test
	@DisplayName("nested violations report the nested path")
	void nestedPathsAreReported() {
		String nestedSchema = """
				{"type":"object","properties":{"user":{"type":"object","properties":{
				  "age":{"type":"integer"}},"required":["age"]}}}""";
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(nestedSchema,
				"{\"user\": {}}");
		assertTrue(failure.isPresent());
		assertEquals("$.user.age", failure.get().fieldPath());
	}

	@Test
	@DisplayName("array item violations report the indexed path")
	void arrayItemPathsAreReported() {
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"x\", \"tags\": [\"ok\", 7]}");
		assertTrue(failure.isPresent());
		assertEquals("INVALID_TYPE", failure.get().errorCode());
		assertEquals("$.tags[1]", failure.get().fieldPath());
	}

	@Test
	@DisplayName("$ref property schemas are skipped instead of resolved")
	void refNodesAreSkipped() {
		String refSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"$ref\":\"#/definitions/name\"}}}";
		assertTrue(ToolArgumentValidator.validate(refSchema, "{\"name\": 1}").isEmpty());
	}

	@Test
	@DisplayName("malformed schema fails explicitly")
	void malformedSchemaFailsClosed() {
		Optional<ToolArgumentValidationFailure> broken = ToolArgumentValidator.validate("{not json", "{}");
		assertTrue(broken.isPresent());
		assertEquals("SCHEMA_MALFORMED", broken.get().errorCode());

		Optional<ToolArgumentValidationFailure> notAnObject = ToolArgumentValidator.validate("[1]", "{}");
		assertTrue(notAnObject.isPresent());
		assertEquals("SCHEMA_MALFORMED", notAnObject.get().errorCode());
	}

	@Test
	@DisplayName("missing or blank schema means no constraints")
	void absentSchemaPasses() {
		assertTrue(ToolArgumentValidator.validate(null, "{\"anything\": 1}").isEmpty());
		assertTrue(ToolArgumentValidator.validate("  ", "{\"anything\": 1}").isEmpty());
	}

	@Test
	@DisplayName("blank arguments become an empty object so required rules still apply")
	void blankArgumentsTreatedAsEmptyObject() {
		assertTrue(ToolArgumentValidator.validate("{}", "").isEmpty());
		Optional<ToolArgumentValidationFailure> failure = ToolArgumentValidator.validate(SCHEMA, "");
		assertTrue(failure.isPresent());
		assertEquals("REQUIRED_MISSING", failure.get().errorCode());
	}

	@Test
	@DisplayName("a fully valid call passes all rules")
	void validCallPasses() {
		assertTrue(ToolArgumentValidator.validate(SCHEMA,
				"{\"name\": \"agent\", \"level\": 3, \"mode\": \"fast\", \"tags\": [\"a\", \"b\"]}").isEmpty());
	}

}
