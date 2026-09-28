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

package io.github.agentic.spring.ai.graph.node;

import io.github.agentic.spring.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QuestionClassifierNodeTest {

	private ChatClient chatClient;

	@BeforeEach
	public void setUp() {
		ChatModel chatModel = new QuestionClassifierChatModel();
		chatClient = ChatClient.builder(chatModel).build();
	}

	private QuestionClassifierNode createNode(Map<String, String> categories, List<String> instructions) {
		return QuestionClassifierNode.builder()
			.chatClient(chatClient)
			.inputTextKey("input")
			.categories(categories)
			.outputKey("output")
			.classificationInstructions(instructions)
			.build();
	}

	private OverAllState createState(Map<String, Object> map) {
		OverAllState state = new OverAllState();
		state.updateState(map);
		return state;
	}

	@Test
	public void testBase() throws Exception {
		QuestionClassifierNode node = createNode(Map.of("1", "正面评价", "2", "负面评价", "3", "中立评价"),
				List.of("请根据输入的评价内容，给出评价的分类结果。"));
		Map<String, Object> apply = node.apply(createState(Map.of("input", "你们的服务做的真好！")));
		assertEquals("1", apply.get("output"));
		Map<String, Object> apply1 = node.apply(createState(Map.of("input", "你们服务做的真差！")));
		assertEquals("2", apply1.get("output"));
	}

	@Test
	public void testVariableCategories() throws Exception {
		QuestionClassifierNode node = createNode(Map.of("1", "{category1}评价", "2", "{category2}评价"),
				List.of("请根据输入的评价内容，给出评价的分类结果。"));
		Map<String, Object> apply = node
			.apply(createState(Map.of("input", "你们的服务做的真好！", "category1", "正面", "category2", "负面")));
		assertEquals("1", apply.get("output"));
		Map<String, Object> apply1 = node
			.apply(createState(Map.of("input", "你们服务做的真差！", "category2", "正面", "category1", "负面")));
		assertEquals("1", apply1.get("output"));
	}

	@Test
	public void testVariableInstructions() throws Exception {
		QuestionClassifierNode node = createNode(Map.of("1", "正面评价", "2", "负面评价"), List.of("{instruction}"));
		Map<String, Object> apply = node
			.apply(createState(Map.of("input", "你们的服务做的真差！", "instruction", "请根据输入的评价内容，给出评价的分类结果。")));
		assertEquals("2", apply.get("output"));
	}

	private static final class QuestionClassifierChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			String promptText = prompt.toString();
			String category = promptText.contains("服务做的真差") ? "负面评价" : "正面评价";
			return new ChatResponse(List.of(new Generation(new AssistantMessage(
					"{\"keywords\": [\"服务\"], \"category_name\": \"" + category + "\"}"))));
		}

	}

}
