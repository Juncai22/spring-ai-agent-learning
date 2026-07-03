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

package com.alibaba.cloud.ai.example.chat.deepseek.controller;

// Note 1: 本类是「换模型」教学的关键对照样例。把它和 DashScopeChatClientController 放一起看，
// 你会发现: 业务代码 (ChatClient 链式调用) 几乎一模一样，区别只在三处:
//   1) 依赖 starter: spring-ai-alibaba-starter-deepseek (而非 dashscope)。
//   2) 注入类型:    DeepSeekChatModel (而非 DashScopeChatModel / 通用 ChatModel)。
//   3) 参数类型:    DeepSeekChatOptions (而非 DashScopeChatOptions)。
// yml 里只需改 spring.ai.deepseek.api-key + base-url + model。
// 这就是 Spring AI 「模型无关性」的核心价值: 换模型 = 换 starter + 改配置，业务零改动。
//
// 额外亮点: 本类提前演示了 MessageChatMemoryAdvisor (多轮对话记忆)，
// 这是 chat-memory-example 模块的预告，先混个眼熟。
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 北极星
 */
@RestController
@RequestMapping("/client")
public class DeepSeekChatClientController {

    private static final String DEFAULT_PROMPT = "你好，介绍下你自己！";

    // Note 2: 字段名首字母大写 (DeepSeekChatClient) 不符合 Java 命名规范，仅作演示，实际项目应小写开头。
    private final ChatClient DeepSeekChatClient;

    // Note 3: 注入的是 DeepSeekChatModel (具体类型)，而非通用 ChatModel 接口。
    // 用具体类型是为了能拿到 DeepSeek 专属的 builder 能力；若不需要专属能力，声明为 ChatModel 更解耦。
    public DeepSeekChatClientController (DeepSeekChatModel chatModel) {

        // Note 4: 这一行信息量很大，拆开看:
        //   ChatClient.builder(chatModel) —— 同样的高级 API，与厂商无关。
        //   MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build()
        //     —— 记忆 Advisor: 让模型记住最近 N 轮对话。MessageWindowChatMemory 是滑动窗口实现，
        //        只保留最近若干条消息，避免上下文无限增长撑爆 token 上限。
        //   defaultAdvisors 可以链式调用多次，叠加多个 Advisor (记忆 + 日志)。
        this.DeepSeekChatClient = ChatClient.builder(chatModel).defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
                // 实现 Logger 的 Advisor
                .defaultAdvisors(new SimpleLoggerAdvisor())
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                // Note 5: DeepSeekChatOptions 与 DashScopeChatOptions 平行，都是各自厂商的参数容器。
                // 这里只设了 temperature，因为 DeepSeek 没有 DashScope 那些联网搜索专属参数——
                // 厂商专属能力差异就体现在 Options 类的不同上。
                .defaultOptions(DeepSeekChatOptions.builder().temperature(0.7d).build()).build();
    }

    /**
     * 使用自定义参数调用DeepSeek模型
     *
     * @return ChatResponse 包含模型响应结果的封装对象
     * @apiNote 当前硬编码指定模型为deepseek-chat，温度参数0.7以平衡生成结果的创造性和稳定性
     */
    // Note 6: 与 dashscope 的 simpleChat 不同，这里返回完整的 ChatResponse (而非 .content() 取文本)。
    // .chatResponse() 暴露元数据 (token、finishReason)，适合需要后处理的场景。
    // 同时演示了「单次调用覆盖默认参数」: new Prompt(文本, 临时 options)，temperature 0.75 覆盖默认 0.7。
    @GetMapping(value = "/ai/customOptions")
    public ChatResponse customOptions () {

        return this.DeepSeekChatClient.prompt(new Prompt(
                "Generate the names of 5 famous pirates.",
                        DeepSeekChatOptions.builder().temperature(0.75).build())
                ).call()
                .chatResponse();
    }

    /**
     * 执行默认提示语的 AI 生成请求
     */
    // Note 7: 标准 ChatClient 同步调用。与 dashscope 版的 /client/simple/chat 写法完全一致——
    // 再次印证: ChatClient API 跨厂商统一，换模型不动业务代码。
    @GetMapping("/ai/generate")
    public String chat () {

        return this.DeepSeekChatClient.prompt(DEFAULT_PROMPT)
                .call()
                .content();
    }

    /**
     * 流式生成接口 - 支持实时获取生成过程的分块响应
     */
    // Note 8: 流式调用同样与 dashscope 版一致。把 .call() 换 .stream() 即可。
    @GetMapping("/ai/stream")
    public Flux<String> stream (HttpServletResponse response) {

        response.setCharacterEncoding("UTF-8");
        return this.DeepSeekChatClient.prompt(DEFAULT_PROMPT)
                .stream()
                .content();
    }
}
