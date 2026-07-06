/*
 * Copyright 2024-2024 the original author or authors.
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

package ai.spring.demo.ai.playground.services;

import java.time.LocalDate;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import static org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor.TOP_K;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 航空公司 "Funnair" 的客户支持助手 —— 本模块的核心装配类。
 * <p>
 * 采用 Spring AI 的 <b>ChatClient + Advisor</b> 路线（区别于 ReactAgent / Graph 路线），
 * 通过 {@link ChatClient.Builder} 一次性装配四件套，构成一个轻量但完整的 AI 客服：
 * <ul>
 *   <li><b>System Prompt</b>：用自然语言写业务规则（角色、SOP、操作约束）</li>
 *   <li><b>PromptChatMemoryAdvisor</b>：多轮记忆，按 chatId 隔离不同会话</li>
 *   <li><b>QuestionAnswerAdvisor</b>：RAG 检索条款文档（每次请求自动检索，区别于"RAG 作为 Tool"的自主检索）</li>
 *   <li><b>Tools</b>：查/改/取消预订（委托给 {@link BookingTools} 注册的函数式工具）</li>
 * </ul>
 * 循环由 ChatClient 内置的工具执行机制驱动：LLM 决定调工具 → ChatClient 自动执行并回传结果
 * → LLM 继续推理，直到不再调工具。无需手写 ReAct 循环或 Graph 拓扑。
 * <p>
 * 输出为 {@link Flux}&lt;String&gt; 流式，前端用 SSE 接收，呈现打字机效果。
 *
 * @author Christian Tzolov
 */
// Note (架构): ★★★ 本模块是「毕业作品」, 演示 Spring AI 的另一条路线——ChatClient + Advisor
//
// Spring AI 有两条路线实现 AI 应用:
//   路线A (本模块): ChatClient + Advisor —— 轻量, ChatClient 内置工具循环, 无需手写 Graph
//   路线B (前面 Agent 模块): ReactAgent / Graph —— 重量, 显式编排节点边, 可控但复杂
//
// ★ 关键区别:
//   本模块没有 ReactAgent, 没有 Graph, 没有 Saver!
//   它用 ChatClient + 3 个 Advisor + 3 个工具, 就实现了「带记忆+RAG+工具调用」的客服
//   工具循环靠 ChatClient 内置机制 (LLM 决定调工具 → ChatClient 自动执行 → 回传 → 继续)
//
// ★ 与第12站 rag-agent 的 RAG 区别:
//   rag-agent:     RAG 作为 Tool, Agent 自主决定要不要检索 (Agentic RAG)
//   本模块:        QuestionAnswerAdvisor 每次请求自动检索 (传统 RAG, 固定先检索)
//   两种 RAG 都有适用场景, 这里用 Advisor 版更简单
@Service
public class CustomerSupportAssistant {

	// Note 1: 持有装配好的 ChatClient (不可变, 构造时一次性建好, 后续复用)。
	// 注意: 这里是 ChatClient (高级 API), 不是 ReactAgent——本模块不用 Agent 框架。
	private final ChatClient chatClient;

	/**
	 * 装配 ChatClient：System Prompt + 三个 Advisor + 三个工具，一次性完成"四合一"绑定。
	 * <p>
	 * 三组配置：
	 * <ul>
	 *   <li>{@code defaultSystem}：写入客服角色与业务 SOP（先验明正身、改签查条款、收费需确认），
	 *       {@code {current_date}} 占位符由运行时注入</li>
	 *   <li>{@code defaultAdvisors}：Memory（记忆）+ QuestionAnswerAdvisor（RAG）+ SimpleLoggerAdvisor（日志）</li>
	 *   <li>{@code defaultToolNames}：按 Bean 名启用三个工具</li>
	 * </ul>
	 * @param modelBuilder ChatClient 构造器（由 DashScope starter 自动注入）
	 * @param vectorStore  向量库，供 QuestionAnswerAdvisor 检索条款
	 * @param chatMemory   对话记忆，供 PromptChatMemoryAdvisor 注入历史
	 */
	// Note 2: ★ 构造器注入三个依赖 (都是 AgentApplication 里声明的 Bean):
	//   modelBuilder  → ChatClient.Builder (DashScope starter 提供, 含默认 model/key)
	//   vectorStore   → 向量库 (给 RAG Advisor 用)
	//   chatMemory    → 对话记忆 (给 Memory Advisor 用)
	public CustomerSupportAssistant(ChatClient.Builder modelBuilder, VectorStore vectorStore, ChatMemory chatMemory) {

		// @formatter:off
		// Note 3: ★★★ 四合一装配——一次性把 Prompt + Advisor + 工具全绑到 ChatClient 上。
		this.chatClient = modelBuilder
				// Note 4: ① defaultSystem——系统提示词, 定义客服角色 + 业务 SOP + 操作约束。
				// 用自然语言写业务规则, LLM 会遵守 (软约束, 业务代码里还有硬约束兜底)。
				// {current_date} 是占位符, 运行时由 chat() 方法注入今天日期。
				.defaultSystem("""
						您是“Funnair”航空公司的客户聊天支持代理。请以友好、乐于助人且愉快的方式来回复。
						您正在通过在线聊天系统与客户互动。
						您能够支持已有机票的预订详情查询、机票日期改签、机票预订取消等操作，其余功能将在后续版本中添加，如果用户问的问题不支持请告知详情。
						在提供有关机票预订详情查询、机票日期改签、机票预订取消等操作之前，您必须始终从用户处获取以下信息：预订号、客户姓名。
						在询问用户之前，请检查消息历史记录以获取预订号、客户姓名等信息，尽量避免重复询问给用户造成困扰。
						在更改预订之前，您必须确保条款允许这样做。
						如果更改需要收费，您必须在继续之前征得用户同意。
						使用提供的功能获取预订详细信息、更改预订和取消预订。
						如果需要，您可以调用相应函数辅助完成。
						请讲中文。

						今天的日期是 {current_date}.
					""")
				// 插件组合
				// Note: ★ 三个 Advisor 体现三种能力:
				//   PromptChatMemoryAdvisor —— 多轮记忆 (按 chatId 隔离, 第4站学过)
				//   QuestionAnswerAdvisor   —— RAG 检索 (每次自动查向量库, 传统 RAG)
				//   SimpleLoggerAdvisor     —— 日志 (调试用)
				.defaultAdvisors(
						// Note 5: ② PromptChatMemoryAdvisor——多轮记忆。
						// before: 按 CONVERSATION_ID (chatId) 取历史消息, 拼进 prompt
						// after:  把本轮 (用户问+AI答) 存回 chatMemory
						// 这样 LLM 能记住之前聊过啥 (如用户上轮说的姓名)
						PromptChatMemoryAdvisor.builder(chatMemory).build(), // Chat Memory
						// new VectorStoreChatMemoryAdvisor(vectorStore)),

						// Note 6: ③ QuestionAnswerAdvisor——RAG 自动检索 (传统 RAG)。
						// 每次请求 before: 用用户消息查 vectorStore, 把相关条款文档拼进 prompt
						// 区别 rag-agent: 那里 RAG 是 Tool (Agent 自主决定调), 这里是 Advisor (每次必检索)
						// 客服场景每个问题都可能涉条款, 固定检索更简单可靠
						QuestionAnswerAdvisor.builder(vectorStore).build(), // RAG
						// new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults()
						// 	.withFilterExpression("'documentType' == 'terms-of-service' && region in ['EU', 'US']")),

						// logger
						// Note 7: SimpleLoggerAdvisor——日志 Advisor, 打印每次 LLM 调用的请求/响应 (调试用)。
						new SimpleLoggerAdvisor()
				)
				// Note 8: ④ defaultToolNames——按 Bean 名启用三个工具。
				// 这三个名字对应 BookingTools 里 @Bean 方法名 (getBookingDetails/changeBooking/cancelBooking)。
				// ChatClient 内置工具循环: LLM 决定调工具 → ChatClient 自动执行 → 结果回传 → LLM 继续
				// 不需要手写 ReAct 循环或 Graph!
				.defaultToolNames(
						"getBookingDetails",
						"changeBooking",
						"cancelBooking"
				).build();
		// @formatter:on
	}

	/**
	 * 与用户进行一轮对话（流式返回）。
	 * <ul>
	 *   <li>{@code current_date} 注入 system prompt，让 LLM 知道"今天"，从而判断 24h/48h 等相对时间</li>
	 *   <li>{@code CONVERSATION_ID = chatId}：按会话 ID 隔离记忆，不同聊天窗口互不串扰</li>
	 *   <li>{@code TOP_K = 100}：RAG 检索返回的文档条数上限</li>
	 *   <li>{@code stream().content()}：以 {@link Flux} 流式吐出文本，配合 SSE 推给前端</li>
	 * </ul>
	 * @param chatId             会话 ID，用于隔离多轮记忆
	 * @param userMessageContent 用户本轮输入
	 * @return 流式生成的回复文本
	 */
	// Note 9: ★ chat 方法——与用户对话一轮, 流式返回。
	// 每次调用: 注入日期 + 用户消息 + Advisor 参数 → 流式返回。
	public Flux<String> chat(String chatId, String userMessageContent) {

		return this.chatClient.prompt()
				// Note 10: 注入今天的日期到 system prompt 的 {current_date} 占位符。
				// LLM 据此判断 "24h内不可改签" 等相对时间规则。
				.system(s -> s.param("current_date", LocalDate.now().toString()))
				// Note 11: 用户本轮消息。
				.user(userMessageContent)
				.advisors(
						// 设置advisor参数，
						// 记忆使用chatId，
						// 拉取最近的100条记录
						// Note 12: ★ 两个运行时 Advisor 参数:
						//   CONVERSATION_ID = chatId  → 记忆按 chatId 隔离, 不同聊天窗口不串扰
						//   TOP_K = 100               → RAG 检索返回最多 100 条文档
						a -> a.param(CONVERSATION_ID, chatId).param(TOP_K, 100))
				.stream()
				// Note 13: ★ stream().content()——流式返回 Flux<String>。
				// 流式: LLM 边生成边返回, 前端 SSE 接收, 打字机效果。
				// 对比 call().content() 是一次性返回完整文本。
				.content();
	}

}
