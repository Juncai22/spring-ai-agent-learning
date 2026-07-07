/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.prompt.controller;

import java.util.HashMap;
import java.util.Map;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prompt/ai")
public class StuffController {

	private final ChatClient chatClient;

	@Value("classpath:/docs/wikipedia-curling.md")
	private Resource docsToStuffResource;

	@Value("classpath:/prompts/qa-prompt.st")
	private Resource qaPromptResource;

	@Autowired
	public StuffController(ChatClient.Builder builder) {
		this.chatClient = builder.build();
	}

	/**
	 * 演示使用特定的 prompt 上下文信息以增强大模型的回答。
	 */
	@GetMapping(value = "/stuff")
	public Flux<String> completion(
			@RequestParam(
					value = "message",
					required = false,
					defaultValue = "Which athletes won the mixed doubles gold medal in curling at the 2022 Winter Olympics?'") String message,
			@RequestParam(value = "stuffit", defaultValue = "false") boolean stuffit
	) {

		// qa-prompt.st 是一个普通 PromptTemplate，里面通常包含 question/context 等占位符。
		// Controller 不直接拼完整提示词，而是只准备变量，模板结构交给资源文件维护。
		PromptTemplate promptTemplate = new PromptTemplate(qaPromptResource);

		Map<String, Object> map = new HashMap<>();
		// question 对应模板里的用户问题占位符，是每次请求都会变化的运行时输入。
		map.put("question", message);

		// stuffit=true 时，把本地文档作为 context 填进 prompt。
		// 这就是最朴素的“上下文增强”：还没有向量检索，只是手动指定要塞入哪份文档。
		if (stuffit) {
			map.put("context", docsToStuffResource);
		}
		else {
			// 不填 context 时，模型只能依赖自身已有知识回答。
			// 对比 stuffit=true 的结果，可以直观看到外部上下文对答案准确性的影响。
			map.put("context", "");
		}

		// promptTemplate.create(map) 会完成变量替换并生成 Prompt。
		// 后续 RAG 模块做的事情，可以理解为“自动选择相关文档片段并填入 context”。
		return chatClient.prompt(promptTemplate.create(map))
				.stream().content();
	}

}
