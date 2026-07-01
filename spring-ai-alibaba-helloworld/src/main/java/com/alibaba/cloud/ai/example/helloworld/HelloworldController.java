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

package com.alibaba.cloud.ai.example.helloworld;

// Note 1: Spring AI 的核心抽象都在 org.springframework.ai.* 下。
// 这里用到的关键概念有四个，先在心里建立这张地图，后面的代码会逐一展开：
//   - ChatClient   : 对话的统一入口（链式 API），屏蔽底层模型差异
//   - ChatModel    : 真正调用大模型的客户端（这里是 OpenAI 兼容实现，指向火山方舟）
//   - Advisor      : 拦截器/中间件，在请求前后做增强（记忆、日志、安全等）
//   - ChatMemory   : 多轮对话历史的存储抽象
import jakarta.servlet.http.HttpServletResponse;
// Note 2: OpenAiChatOptions 是 OpenAI 兼容协议专用的参数对象。
// 因为火山方舟 Ark 的 coding 入口走的是 OpenAI 兼容协议，所以用这个类。
// 如果换成 DashScope（阿里通义），这里就要改成 DashScopeChatOptions。
// 体会一下：换模型厂商 = 换 Options 类 + 换 starter，而 ChatClient 业务代码不动。
import org.springframework.ai.openai.OpenAiChatOptions;
// Note 3: MessageChatMemoryAdvisor 是一个内置 Advisor，作用是：
// 在每次发请求给模型前，把该会话的历史消息拼到请求里，让模型“记得”之前说过什么。
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
// Note 4: ChatMemory 是对话记忆的抽象接口。Spring AI 并不规定存哪里——
// 可以存内存、Redis、数据库。这里用的是它的内存实现 MessageWindowChatMemory。
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
// Note 5: Flux 来自 Reactor（响应式流）。返回 Flux<String> 表示“流式”接口——
// 模型每生成一小段文字就推给客户端一次，而不是等全部生成完再返回。
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

// Note 6: @RestController = @Controller + @ResponseBody。
// 所有方法的返回值会直接作为 HTTP 响应体，不会被解析成视图名。
// 这是 Spring Boot 写 RESTful API 的标准注解，与 AI 无关。
@RestController
// Note 7: @RequestMapping("/helloworld") 给本类所有接口加统一前缀。
// 类内每个 @GetMapping 的路径都会拼在这个前缀后面，例如 /helloworld/simple/chat。
@RequestMapping("/helloworld")
public class HelloworldController {

    // Note 8: 系统提示词（System Prompt）。
    // 它会作为对话的“背景设定”发给模型，用来塑造模型的身份和行为风格。
    // 与用户的 query 不同：系统提示词每轮都隐式带上，用户看不到，但模型会遵循。
    private static final String DEFAULT_PROMPT = "你是一个博学的智能聊天助手，请根据用户提问回答！";

    // Note 9: 持有一个构建好的 ChatClient 实例。
    // ChatClient 是“配置好默认值”的对话入口，线程安全，可在多个请求间复用。
    // 变量名叫 dashScopeChatClient 是历史遗留（原本用 DashScope），现在底层已换成火山方舟。
    private final ChatClient dashScopeChatClient;

    // Note 10: 构造器注入。Spring 会自动注入一个 ChatClient.Builder Bean。
    // 这个 Builder 由 spring-ai-starter-model-openai 根据配置自动装配，
    // 它背后绑定了一个 ChatModel（真正调 HTTP 接口的客户端）。
    // 我们用 Builder 设定“默认值”，然后 .build() 出一个可复用的 ChatClient。
    // 也可以使用如下的方式注入 ChatClient
    public HelloworldController(ChatClient.Builder chatClientBuilder) {

        this.dashScopeChatClient = chatClientBuilder
                // Note 11: defaultSystem 设定默认系统提示词。
                // 之后每次调用都会自动带上这段设定，除非调用时显式覆盖。
                .defaultSystem(DEFAULT_PROMPT)
                // Note 12: defaultAdvisors 注册默认 Advisor 链。
                // Advisor 之间有顺序，按注册顺序在请求/响应两个方向上依次执行。
                // 这里第一个：MessageChatMemoryAdvisor —— 负责多轮记忆。
                .defaultAdvisors(
                    // Note 13: MessageWindowChatMemory 是一种“滑动窗口”记忆策略：
                    // 只保留最近 N 条消息（默认 20 条），老的自动丢弃。
                    // 这样既能让模型有上下文，又不会因为历史太长撑爆 token 上限。
                    // .builder().build() 用的是默认窗口大小；要自定义就 .maxMessages(10)。
                    MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
                // Note 14: 第二个 Advisor：SimpleLoggerAdvisor。
                // 它把每次请求和响应打印到日志，方便调试观察实际发给模型的内容。
                // 开发期很有用，生产环境可按需移除以减少日志量。
                .defaultAdvisors(new SimpleLoggerAdvisor())
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                // Note 15: defaultOptions 设定默认模型参数。
                // OpenAiChatOptions 对应 OpenAI 兼容协议的参数集：topP、temperature、maxTokens 等。
                // 这里只设了 topP(0.7)：核采样，控制生成的多样性，值越小越保守、越大越发散。
                // 注意方法名是 topP（OpenAI 风格）；DashScope 那边也叫 topP，但 maxTokens
                // 在 DashScope 里叫 maxToken（无 s），换实现时要留意这种细节差异。
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .topP(0.7)
                                .build()
                )
                // Note 16: build() 把所有默认值固化成一个 ChatClient。
                // 之后每次 .prompt() 调用都会复用这些默认值，调用时还能临时覆盖。
                .build();
    }

    /**
     * ChatClient 简单调用
     */
    // Note 17: 最简单的同步对话接口。GET /helloworld/simple/chat?query=...
    // query 可省略，省略时用 defaultValue。
    @GetMapping("/simple/chat")
    public String simpleChat(@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query) {

        // Note 18: 这就是 Spring AI 的核心链式调用，逐段拆解：
        //   .prompt(query)  —— 以用户消息 query 开始一次请求构建
        //   .call()         —— 同步发起调用，阻塞等待模型完整返回
        //   .content()      —— 从响应中取出纯文本内容（去掉元数据）
        // 同步调用最简单，但用户要等整段生成完才能看到结果。
        return dashScopeChatClient.prompt(query).call().content();
    }

    /**
     * ChatClient 流式调用
     */
    // Note 19: 流式接口。GET /helloworld/stream/chat?query=...
    // 返回类型是 Flux<String>：Spring 会把它当成 Server-Sent Events (SSE) 逐块推送。
    // 客户端用 curl -N 或 EventSource 能实时看到文字一段段冒出来，体验更好。
    @GetMapping("/stream/chat")
    public Flux<String> streamChat(@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query, HttpServletResponse response) {

        // Note 20: 显式指定响应编码为 UTF-8，避免中文乱码。
        // 这是 Servlet 层的设置，与 AI 无关，但流式接口里很容易踩中文乱码的坑。
        response.setCharacterEncoding("UTF-8");
        // Note 21: 与 simpleChat 的唯一区别是 .stream() 替代了 .call()。
        // .stream().content() 返回 Flux<String>，模型每生成一个 token 块就发一次。
        // 同一个 ChatClient、同一套默认值，只是“怎么拿结果”不同——这是 Spring API 设计的优雅之处。
        return dashScopeChatClient.prompt(query).stream().content();
    }

    /**
     * ChatClient 使用自定义的 Advisor 实现功能增强.
     * eg:
     * <a href="http://127.0.0.1:18080/helloworld/advisor/chat/123?query=">...</a>你好，我叫jack，之后的会话中都带上我的名字
     * 你好，jack！很高兴认识你。在接下来的对话中，我会记得带上你的名字。有什么想聊的吗？
     * <a href="http://127.0.0.1:18080/helloworld/advisor/chat/123?query=">...</a>我叫什么名字？
     * 你叫jack呀。有什么事情想要分享或者讨论吗，jack？
     * <p>
     * refer: <a href="https://docs.spring.io/spring-ai/reference/api/chat-memory.html#_memory_in_chat_client">...</a>
     */
    // Note 22: 带会话 ID 的多轮记忆接口。
    // 路径变量 {conversationId} 用来区分不同会话——就像微信的聊天窗口，每个 ID 一份独立历史。
    @GetMapping("/advisor/chat/{conversationId}")
    public Flux<String> advisorChat(
            HttpServletResponse response,
            // Note 23: @PathVariable 从 URL 路径取会话 ID。
            // 同一个 ID 的多次调用共享同一份记忆；换 ID 就是新对话。
            @PathVariable String conversationId,
            @RequestParam String query
    ) {

        response.setCharacterEncoding("UTF-8");

        // Note 24: 这次调用展示了“临时参数透传给 Advisor”的机制。
        // .advisors(a -> a.param(...)) 不是新增 Advisor，而是给已有 Advisor 传运行时参数。
        return this.dashScopeChatClient.prompt(query)
                // Note 25: ChatMemory.CONVERSATION_ID 是一个约定好的参数 key。
                // MessageChatMemoryAdvisor 会读取这个参数，用它去 ChatMemory 里取/存历史。
                // 没传的话，所有对话会混在同一个默认会话里——多用户场景下会串记忆。
                // 传了 conversationId，每个会话的历史就彼此隔离了。
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                ).stream().content();
    }

	/**
     * ChatClient 新的聊天接口，支持流式输出和自定义 ChatOptions 配置
     * eg:
     * <a href="http://127.0.0.1:18080/helloworld/advisor/newChat?query=">...</a>你好&topP=0.8&temperature=0.9
     */
	// Note 26: 演示“运行时动态覆盖模型参数”。前面的接口都用构造器里设的默认 topP=0.7，
	// 这里允许调用方通过 query 参数临时指定 topP/temperature/maxTokens，覆盖默认值。
	@GetMapping("/advisor/newChat")
	public Flux<String> newChat(
			HttpServletResponse response,
			@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query,
			// Note 27: required = false 表示这些参数可选。不传就是 null，不会报 400。
			@RequestParam(value = "topP", required = false) Double topP,
			@RequestParam(value = "temperature", required = false) Double temperature,
			@RequestParam(value = "maxTokens", required = false) Integer maxToken) {

		response.setCharacterEncoding("UTF-8");

		// 构建 ChatOptions
		// Note 28: 用 Builder 模式按需拼装参数。只把调用方真正传了的参数设进去，
		// 没传的保持默认（不调用对应方法即可）。这种“按需构建”是参数对象的常见写法。
		OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();

		if (topP != null) {
			// Note 29: topP 核采样（0~1）。和 temperature 都控制随机性，但机制不同：
			// temperature 是整体缩放概率分布；topP 是只从累积概率前 P 的候选词里选。
			// 一般调一个即可，两个都调容易过度。
			optionsBuilder.topP(topP);
		}
		if (temperature != null) {
			// Note 30: temperature 温度（0~2）。越高越发散有创意，越低越确定保守。
			// 写代码/问答用低值（0~0.3），写诗/脑暴用高值（0.7~1.2）。
			optionsBuilder.temperature(temperature);
		}
		if (maxToken != null) {
			// Note 31: maxTokens 限制模型最多生成多少 token，用来控制成本和响应长度。
			// 注意 OpenAI 风格是 maxTokens（带 s）；前面提过 DashScope 是 maxToken。
			optionsBuilder.maxTokens(maxToken);
		}

		// Note 32: .options(...) 把刚才构建的参数对象挂到“这一次”调用上。
		// 它只影响本次请求，不会改 ChatClient 的默认值——下次不带 .options 还是 0.7 的 topP。
		// 这就是“默认值 + 单次覆盖”的灵活配置模型：常用值设默认，特殊场景临时调。
		return this.dashScopeChatClient.prompt(query)
				.options(optionsBuilder.build())
				.stream()
				.content();
	}

}
