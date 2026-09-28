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
package io.github.agentic.spring.ai.graph.agent.tools;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebFetchToolTest {

	private WebFetchTool webFetchTool;

	@BeforeEach
	void setUp() {
		ChatModel chatModel = mock(ChatModel.class);
		ChatResponse mockResponse = new ChatResponse(
				List.of(new Generation(new AssistantMessage("Mocked summary"))));
		when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);

		ChatClient chatClient = ChatClient.builder(chatModel).build();
		webFetchTool = WebFetchTool.builder(chatClient)
			.maxContentLength(100_000)
			.maxCacheSize(100)
			.maxRetries(1)
			.buildWebFetchTool();
	}

	@Test
	void testEmptyUrlReturnsError() {
		String result = webFetchTool.apply(new WebFetchTool.Request("", "Summarize"), new ToolContext(Collections.emptyMap()));
		assertTrue(result.startsWith("Error:"));
		assertTrue(result.contains("URL cannot be empty"));
	}

	@Test
	void testBlankUrlReturnsError() {
		String result = webFetchTool.apply(new WebFetchTool.Request("   ", "Summarize"), new ToolContext(Collections.emptyMap()));
		assertTrue(result.startsWith("Error:"));
		assertTrue(result.contains("URL cannot be empty"));
	}

	@Test
	void testInvalidUrlFormatReturnsError() {
		String result = webFetchTool.apply(new WebFetchTool.Request("not-a-valid-url", "Summarize"),
				new ToolContext(Collections.emptyMap()));
		assertTrue(result.startsWith("Error:"));
		assertTrue(result.contains("Invalid URL"));
	}

	@Test
	void testInvalidUrlMissingSchemeReturnsError() {
		String result = webFetchTool.apply(new WebFetchTool.Request("example.com/path", "Summarize"),
				new ToolContext(Collections.emptyMap()));
		assertTrue(result.startsWith("Error:"));
		assertTrue(result.contains("Invalid URL"));
	}

	@Test
	void rejectsLoopbackUrlBeforeFetching() {
		String result = webFetchTool.apply(new WebFetchTool.Request("http://127.0.0.1/status", "Summarize"),
				new ToolContext(Collections.emptyMap()));

		assertTrue(result.startsWith("Error"));
		assertTrue(result.contains("not allowed"));
	}

	@Test
	void rejectsRedirectToLoopbackUrl() throws Exception {
		ChatModel chatModel = mock(ChatModel.class);
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		FakeAddressResolver resolver = new FakeAddressResolver(Map.of(
				"93.184.216.34", new InetAddress[] { InetAddress.getByName("93.184.216.34") },
				"127.0.0.1", new InetAddress[] { InetAddress.getByName("127.0.0.1") }));
		CapturingTransport transport = new CapturingTransport(WebFetchTool.FetchResponse.of(302,
				URI.create("https://93.184.216.34"), Map.of("Location", List.of("https://127.0.0.1/admin")), ""));
		WebFetchTool tool = WebFetchTool.builder(chatClient)
			.addressResolver(resolver)
			.httpTransport(transport)
			.maxRetries(0)
			.buildWebFetchTool();

		String result = tool.apply(new WebFetchTool.Request("https://93.184.216.34", "Summarize"),
				new ToolContext(Collections.emptyMap()));

		assertTrue(result.startsWith("Error"));
		assertTrue(result.contains("not allowed"));
		assertEquals(1, transport.requestedUris.size());
	}

	@Test
	void pinsFetchToValidatedAddressWhileKeepingOriginalHost() throws Exception {
		ChatClient chatClient = chatClient("Pinned summary");
		FakeAddressResolver resolver = new FakeAddressResolver(Map.of("example.com",
				new InetAddress[] { InetAddress.getByName("93.184.216.34") }));
		CapturingTransport transport = new CapturingTransport(WebFetchTool.FetchResponse.of(200,
				URI.create("https://example.com/article"), Map.of(), "<html>ok</html>"));

		WebFetchTool tool = WebFetchTool.builder(chatClient)
			.addressResolver(resolver)
			.httpTransport(transport)
			.maxRetries(0)
			.buildWebFetchTool();

		String result = tool.apply(new WebFetchTool.Request("https://example.com/article", "Summarize"),
				new ToolContext(Collections.emptyMap()));

		assertEquals("Pinned summary", result);
		assertEquals(1, resolver.resolveCount);
		assertEquals("example.com", transport.requestedUris.get(0).getHost());
		assertEquals(InetAddress.getByName("93.184.216.34"), transport.pinnedAddresses.get(0)[0]);
	}

	@Test
	void resolvesAndPinsEachRedirectHop() throws Exception {
		ChatClient chatClient = chatClient("Redirect summary");
		FakeAddressResolver resolver = new FakeAddressResolver(Map.of(
				"example.com", new InetAddress[] { InetAddress.getByName("93.184.216.34") },
				"example.org", new InetAddress[] { InetAddress.getByName("93.184.216.35") }));
		CapturingTransport transport = new CapturingTransport(
				WebFetchTool.FetchResponse.of(302, URI.create("https://example.com/start"),
						Map.of("Location", List.of("https://example.org/final")), ""),
				WebFetchTool.FetchResponse.of(200, URI.create("https://example.org/final"), Map.of(),
						"<html>ok</html>"));

		WebFetchTool tool = WebFetchTool.builder(chatClient)
			.addressResolver(resolver)
			.httpTransport(transport)
			.maxRetries(0)
			.buildWebFetchTool();

		String result = tool.apply(new WebFetchTool.Request("https://example.com/start", "Summarize"),
				new ToolContext(Collections.emptyMap()));

		assertEquals("Redirect summary", result);
		assertEquals(2, resolver.resolveCount);
		assertEquals("example.com", transport.requestedUris.get(0).getHost());
		assertEquals("example.org", transport.requestedUris.get(1).getHost());
		assertEquals(InetAddress.getByName("93.184.216.34"), transport.pinnedAddresses.get(0)[0]);
		assertEquals(InetAddress.getByName("93.184.216.35"), transport.pinnedAddresses.get(1)[0]);
	}

	@Test
	void cacheKeyDistinguishesPromptsWithSameJavaHashCode() throws Exception {
		assertEquals("FB".hashCode(), "Ea".hashCode());
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenReturn(
				new ChatResponse(List.of(new Generation(new AssistantMessage("First summary")))),
				new ChatResponse(List.of(new Generation(new AssistantMessage("Second summary")))));
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		FakeAddressResolver resolver = new FakeAddressResolver(Map.of("example.com",
				new InetAddress[] { InetAddress.getByName("93.184.216.34") }));
		CapturingTransport transport = new CapturingTransport(
				WebFetchTool.FetchResponse.of(200, URI.create("https://example.com/article"), Map.of(),
						"<html>first</html>"),
				WebFetchTool.FetchResponse.of(200, URI.create("https://example.com/article"), Map.of(),
						"<html>second</html>"));

		WebFetchTool tool = WebFetchTool.builder(chatClient)
			.addressResolver(resolver)
			.httpTransport(transport)
			.maxRetries(0)
			.buildWebFetchTool();

		String firstResult = tool.apply(new WebFetchTool.Request("https://example.com/article", "FB"),
				new ToolContext(Collections.emptyMap()));
		String secondResult = tool.apply(new WebFetchTool.Request("https://example.com/article", "Ea"),
				new ToolContext(Collections.emptyMap()));

		assertEquals("First summary", firstResult);
		assertEquals("Second summary", secondResult);
		assertEquals(2, transport.requestedUris.size());
	}

	@Test
	@SuppressWarnings("unchecked")
	void testCacheRespectsMaxCacheSize() throws Exception {
		int maxCacheSize = 10;
		ChatModel chatModel = mock(ChatModel.class);
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		WebFetchTool tool = WebFetchTool.builder(chatClient).maxCacheSize(maxCacheSize).buildWebFetchTool();

		Field cacheField = WebFetchTool.class.getDeclaredField("urlCache");
		cacheField.setAccessible(true);
		Cache<String, String> cache = (Cache<String, String>) cacheField.get(tool);

		for (int i = 0; i < maxCacheSize * 10; i++) {
			cache.put("https://example.com/" + i + "::prompt::0", "content-" + i);
		}
		cache.cleanUp();

		assertTrue(cache.estimatedSize() <= maxCacheSize,
				"cache size " + cache.estimatedSize() + " must not exceed maxCacheSize " + maxCacheSize);
	}

	@Test
	void testBuilderBuildsToolCallback() {
		ChatModel chatModel = mock(ChatModel.class);
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		var toolCallback = WebFetchTool.builder(chatClient).build();
		assertNotNull(toolCallback);
	}

	private static ChatClient chatClient(String responseContent) {
		ChatModel chatModel = mock(ChatModel.class);
		ChatResponse mockResponse = new ChatResponse(
				List.of(new Generation(new AssistantMessage(responseContent))));
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);
		return ChatClient.builder(chatModel).build();
	}

	private static class FakeAddressResolver implements WebFetchTool.AddressResolver {

		private final Map<String, InetAddress[]> addressesByHost;

		private int resolveCount;

		FakeAddressResolver(Map<String, InetAddress[]> addressesByHost) {
			this.addressesByHost = addressesByHost;
		}

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			this.resolveCount++;
			InetAddress[] addresses = this.addressesByHost.get(host);
			if (addresses == null) {
				throw new UnknownHostException(host);
			}
			return addresses;
		}
	}

	private static class CapturingTransport implements WebFetchTool.HttpTransport {

		private final List<WebFetchTool.FetchResponse> responses;

		private final List<URI> requestedUris = new ArrayList<>();

		private final List<InetAddress[]> pinnedAddresses = new ArrayList<>();

		CapturingTransport(WebFetchTool.FetchResponse... responses) {
			this.responses = List.of(responses);
		}

		@Override
		public WebFetchTool.FetchResponse get(URI uri, InetAddress[] pinnedAddresses) {
			this.requestedUris.add(uri);
			this.pinnedAddresses.add(pinnedAddresses);
			return this.responses.get(this.requestedUris.size() - 1);
		}
	}

}
