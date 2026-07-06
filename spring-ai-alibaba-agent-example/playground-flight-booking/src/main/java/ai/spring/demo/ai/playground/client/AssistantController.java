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

package ai.spring.demo.ai.playground.client;

import ai.spring.demo.ai.playground.services.CustomerSupportAssistant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


/**
 * AI 助手聊天接口 —— 前端与 {@link CustomerSupportAssistant} 之间的 SSE 桥梁。
 * <p>把 HTTP 请求转成会话 ID + 用户消息，调用助手并以 {@code text/event-stream} 流式返回。
 */
@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

	private final CustomerSupportAssistant agent;

	public AssistantController(CustomerSupportAssistant agent) {
		this.agent = agent;
	}

	/**
	 * SSE 流式聊天接口。
	 * <p>以 {@code text/event-stream} 推送 {@link Flux}，前端逐块接收呈现打字机效果。
	 * @param chatId      会话 ID（隔离多轮记忆）
	 * @param userMessage 用户输入
	 * @return 流式回复
	 */
	@RequestMapping(path="/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> chat(@RequestParam(name = "chatId") String chatId,
							 @RequestParam(name = "userMessage") String userMessage) {
		return agent.chat(chatId, userMessage);
	}

}
