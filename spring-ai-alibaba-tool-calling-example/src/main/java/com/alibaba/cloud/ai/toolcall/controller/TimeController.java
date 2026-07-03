/*
 * Copyright 2024-2025 the original author or authors.
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
package com.alibaba.cloud.ai.toolcall.controller;

// Note 1: 本 Controller 是「Tool Calling 价值展示」的最小教学案例——
//   两个接口做同一件事,一个挂 Tool 一个不挂,对比效果。
//
// 用户问「现在北京时间几点」:
//   /chat:           LLM 没有 Tool,只能瞎答 (基于训练数据,可能答错或含糊)
//   /chat-tool-method:  LLM 有 Tool,真的去查 → 答案精确
//
// 这是理解 Tool Calling 价值最直接的演示。
import com.alibaba.cloud.ai.toolcall.component.TimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/time")
public class TimeController {

    private final ChatClient dashScopeChatClient;

    private final TimeTools timeTools;

    public TimeController(ChatClient chatClient, TimeTools timeTools) {

        this.dashScopeChatClient = chatClient;
        this.timeTools = timeTools;
    }

    /**
     * No Tool
     */
    // Note 2: 对照组——不挂 Tool。
    // LLM 只能基于训练数据回答,可能:
    //   - 含糊地说「大概下午」(没具体时间)
    //   - 瞎编一个时间 (训练数据里有, 但可能过时)
    //   - 答非所问
    // 这暴露了 LLM 的根本缺陷: **没有实时信息, 没有调用外部系统的能力**。
    @GetMapping("/chat")
    public String simpleChat(@RequestParam(value = "query", defaultValue = "请告诉我现在北京时间几点了") String query) {

        return dashScopeChatClient.prompt(query).call().content();
    }

    /**
     * Methods as Tools
     */
    // Note 3: ★★★ 关键调用: .tools(timeTools)。
    // 这是 Spring AI 提供的「批量挂载工具」API——把整个 Tools 类的所有 @Tool 方法都暴露给 LLM。
    //
    // 内部流程 (调用时 Spring AI 自动完成):
    //   1. 反射 TimeTools, 找到所有 @Tool 方法
    //   2. 提取 description + 参数 schema, 构造成「工具清单」发给 LLM
    //   3. LLM 决定调用 getCityTime("Asia/Shanghai")
    //   4. Spring AI 反序列化 LLM 返回的参数, 调用实际方法
    //   5. 把方法返回值塞回 LLM, 让 LLM 整理成自然语言回答
    //
    // 对比 /chat 的区别就一行: .tools(timeTools) —— 这一行让 LLM 从「瞎答」变成「动手查」。
    @GetMapping("/chat-tool-method")
    public String chatWithTimeFunction(@RequestParam(value = "query", defaultValue = "请告诉我现在北京时间几点了") String query) {

        return dashScopeChatClient.prompt(query).tools(timeTools).call().content();
    }

}
