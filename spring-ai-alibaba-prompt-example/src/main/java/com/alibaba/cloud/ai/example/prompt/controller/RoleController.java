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

import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/example/ai")
public class RoleController {

	private final ChatClient chatClient;

	/**
	 * 加载 System prompt tmpl.
	 */
	@Value("classpath:/prompts/system-message.st")
	private Resource systemResource;

	@Autowired
	public RoleController(ChatClient.Builder builder) {
		this.chatClient = builder.build();
	}

	@GetMapping("/roles")
	public Flux<String> generate(
			@RequestParam(
					value = "message",
					required = false,
					defaultValue = "Tell me about three famous pirates from the Golden Age of Piracy and why they did.  Write at least a sentence for each pirate.") String message,
			@RequestParam(value = "name", required = false, defaultValue = "Bob") String name,
			@RequestParam(value = "voice", required = false, defaultValue = "pirate") String voice
	) {

		// 用户问题单独作为 UserMessage。它代表本轮对话中用户真正提出的问题，
		// 不应该和系统角色设定混在一个字符串里，否则后续很难复用系统提示词。
		UserMessage userMessage = new UserMessage(message);

		// SystemPromptTemplate 会从 classpath:/prompts/system-message.st 读取模板。
		// 这个模板通常用于定义模型的“角色、语气、边界和回答风格”，属于比用户问题更高优先级的约束。
		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemResource);
		// createMessage 会把模板里的 {name}、{voice} 等占位符替换成运行时参数，
		// 最终得到一个 SystemMessage。这样提示词主体可以放在资源文件里维护，Controller 只负责传变量。
		Message systemMessage = systemPromptTemplate.createMessage(Map.of("name", name, "voice", voice));

		// Prompt 可以同时携带多条 Message。这里把用户问题和渲染后的系统角色设定一起交给模型，
		// 模型会综合两部分内容生成答案；stream().content() 表示只流式返回文本内容，不暴露完整 ChatResponse。
		return chatClient.prompt(
						new Prompt(List.of(
								userMessage,
								systemMessage)))
				.stream().content();
	}

}
