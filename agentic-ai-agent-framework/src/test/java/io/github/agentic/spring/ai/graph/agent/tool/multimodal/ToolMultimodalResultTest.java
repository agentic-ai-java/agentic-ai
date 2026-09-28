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
package io.github.agentic.spring.ai.graph.agent.tool.multimodal;

import org.junit.jupiter.api.Test;

import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMultimodalResultTest {

	@Test
	void preservesUrlAndBase64InSpringAiMedia() {
		String url = "https://example.com/image.png";
		String base64 = "aW1hZ2U=";

		Media media = ToolMultimodalResult.mediaFromUrlAndBase64(url, base64, MimeTypeUtils.IMAGE_PNG);
		String serialized = assertInstanceOf(String.class, media.getData());
		ToolMultimodalResult.MediaFormats formats = ToolMultimodalResult.MediaFormats.deserialize(serialized);

		assertNotNull(formats);
		assertEquals(url, formats.url());
		assertEquals(base64, formats.base64());
	}

	@Test
	void convertsCombinedMediaToRequestedOutputFormat() {
		String url = "https://example.com/image.png";
		String base64 = "aW1hZ2U=";
		ToolMultimodalResult result = ToolMultimodalResult.of("image",
				ToolMultimodalResult.mediaFromUrlAndBase64(url, base64, MimeTypeUtils.IMAGE_PNG));

		String urlJson = new MultimodalToolCallResultConverter(OutputFormat.url).convert(result,
				ToolMultimodalResult.class);
		String base64Json = new MultimodalToolCallResultConverter(OutputFormat.base64).convert(result,
				ToolMultimodalResult.class);

		assertTrue(urlJson.contains("\"url\":\"" + url + "\""));
		assertTrue(base64Json.contains("\"data\":\"data:image/png;base64," + base64 + "\""));
	}

	@Test
	void convertsBase64OnlyMediaWithoutUrl() {
		String base64 = "YXVkaW8=";
		ToolMultimodalResult result = ToolMultimodalResult.of("audio",
				ToolMultimodalResult.mediaFromBase64(base64, MimeTypeUtils.parseMimeType("audio/mpeg")));

		String json = new MultimodalToolCallResultConverter(OutputFormat.base64).convert(result,
				ToolMultimodalResult.class);

		assertTrue(json.contains("\"data\":\"data:audio/mpeg;base64," + base64 + "\""));
	}
}
