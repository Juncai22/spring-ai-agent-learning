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

package com.alibaba.cloud.ai.example.chat.qwq.advisor;

// Note 1: 本类是「自定义 Advisor」的典型示例，是 chat-example 模块里最值得深入学的一个进阶概念。
//
// 背景: QwQ / DeepSeek-R1 等「推理模型 (Reasoning Model)」在给出最终答案前，会先输出一段
// 「思考过程 (reasoning content)」。这段思考不在正文里，而是藏在响应的 metadata 中。
// 默认情况下用户看不到思考过程。本 Advisor 的作用: 把思考过程提取出来，拼到正文前面，
// 用 <think>...</think> 标签包裹 (类似 DeepSeek 官网展示效果)。
//
// 更大的价值: Advisor 是 Spring AI 的核心扩展点。学会它就能拦截/改写任何请求与响应，
// 后续的记忆 (Memory)、RAG (检索增强)、日志、内容过滤全都是用 Advisor 实现的。
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 * Incorporate DeepSeek-R1's reasoning content into the output
 */

// Note 2: 实现 BaseAdvisor 接口 (Spring AI 1.1 的统一 Advisor 抽象)。
// BaseAdvisor 把拦截逻辑拆成两个钩子:
//   before(): 请求发出前调用，可改写 prompt、注入上下文 (RAG 的检索结果就在这里塞进去)。
//   after():  响应返回后调用，可改写输出 (本类在此提取思考过程)。
// Advisor 之间会串成一条链 (AdvisorChain)，按 order 顺序依次执行。
public class ReasoningContentAdvisor implements BaseAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(ReasoningContentAdvisor.class);

	// Note 3: order 决定本 Advisor 在链中的执行顺序。before 阶段按 order 升序执行，
	// after 阶段按 order 降序执行 (洋葱模型: 先进后出)。例如 order=0 的 before 最先、after 最后。
	// 通过构造器传入而非硬编码，便于使用方灵活编排多个 Advisor 的顺序。
	private final int order;

	// Note 4: 允许传 null，默认 0。这是一种防御式编程，避免调用方必须传值。
	public ReasoningContentAdvisor(Integer order) {
		this.order = order != null ? order : 0;
	}

	@Override
	public int getOrder() {

		return this.order;
	}

	// Note 5: before 钩子——本例不需要修改请求，直接原样返回 chatClientRequest。
	// 如果要做 RAG，就在这里: 检索知识库 -> 把结果拼进 prompt 的 system/context -> 返回新 request。
	@Override
	public ChatClientRequest before(@NotNull final ChatClientRequest chatClientRequest, @NotNull final AdvisorChain advisorChain) {
		return chatClientRequest;
	}

	// Note 6: after 钩子——核心逻辑全在这里。拿到模型响应，提取 reasoningContent，改写正文。
	@Override
	public ChatClientResponse after(@NotNull final ChatClientResponse chatClientResponse, @NotNull final AdvisorChain advisorChain) {
		// Note 7: 从包装层 ChatClientResponse 取出底层 ChatResponse。流式场景下某一块可能没有响应，
		// 所以先判空，避免 NPE——响应式编程里空值是常态，必须时刻防御。
		ChatResponse resp = chatClientResponse.chatResponse();
		if (Objects.isNull(resp)) {

			return chatClientResponse;
		}

		// Note 8: resp.getResults() 是 Generation 列表 (通常长度为 1)。取第一个的 output.metadata。
		// reasoningContent 就是推理模型藏在 metadata 里的「思考过程」字段。
		// 用 String.valueOf 包装，即使取不到值也不会抛异常 (返回字符串 "null")。
		logger.debug(String.valueOf(resp.getResults().get(0).getOutput().getMetadata()));
		String reasoningContent = String.valueOf(resp.getResults().get(0).getOutput().getMetadata().get("reasoningContent"));

		// Note 9: 只有当确实存在思考内容时才改写。StringUtils.hasText 会排除 null、空串、纯空白。
		// 普通模型 (非推理模型) 不会有这个字段，此时直接原样返回，不影响原有行为。
		if (StringUtils.hasText(reasoningContent)) {
			// Note 10: 把每个 Generation 改造成「思考 + 正文」的新版本。
			// stream().map().toList() 是 Java 16+ 的函数式集合变换写法，等价于 for 循环重建列表。
			List<Generation> thinkGenerations = resp.getResults().stream()
					.map(generation -> {
						AssistantMessage output = generation.getOutput();
						// Note 11: 用 AssistantMessage.builder() 重建消息。
						// .content() 设新正文: <think>思考过程</think> + 原始正文。
						//   <think> 标签是社区惯例 (DeepSeek/ChatGLM 都用)，前端可据此折叠展示思考。
						// .properties() / .toolCalls() / .media(): 保留原消息的其他属性，避免丢失。
						AssistantMessage thinkAssistantMessage = AssistantMessage.builder()
							.content(String.format("<think>%s</think>", reasoningContent) + output.getText())
							.properties(output.getMetadata())
							.toolCalls(output.getToolCalls())
							.media(output.getMedia())
							.build();
						// Note 12: Generation = AssistantMessage + 它自己的 metadata，需一并保留。
						return new Generation(thinkAssistantMessage, generation.getMetadata());
					}).toList();

			// Note 13: ChatResponse.builder().from(resp) 以原响应为模板，只替换 generations 字段。
			// 这样 token 用量、模型名等顶层 metadata 都被完整保留——只改了正文，不动其他信息。
			ChatResponse thinkChatResp = ChatResponse.builder().from(resp).generations(thinkGenerations).build();
			// Note 14: 把改造后的 ChatResponse 重新包回 ChatClientResponse 返回，下游 (调用方/下一个 Advisor)
			// 拿到的就是带 <think> 标签的版本。洋葱模型的 after 阶段: 改写会向外层传递。
			return ChatClientResponse.builder().chatResponse(thinkChatResp).build();

		}

		// Note 15: 没有思考内容时原样返回——这是 Advisor 编写的良好习惯: 不破坏默认行为。
		return chatClientResponse;
	}
}
