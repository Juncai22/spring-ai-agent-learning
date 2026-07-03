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

// Note 1: 本 Controller 演示 Tool Calling 的第二种暴露方式: FunctionToolCallback (编程式挂载)。
//
// 对比 TimeController 的两种方式:
//   方式 1 (.tools() 批量挂载):   把一个 Tools 类的所有 @Tool 方法打包给 LLM。
//   方式 2 (.toolCallbacks() 编程式):  把一个 Service (Function<I, O>) 单个挂载,名字/描述手动指定。
//
// 方式 2 的特点:
//   - 不需要写 Tools 包装类,直接复用任意 Service
//   - 名字/描述在挂载时动态指定, 更灵活
//   - 适合「给一个外部组件快速包装成 Tool」的场景
import com.alibaba.cloud.ai.toolcalling.weather.WeatherService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/weather")
public class WeatherController {

    private final ChatClient dashScopeChatClient;

    private final WeatherService weatherService;

    public WeatherController(ChatClient chatClient, WeatherService weatherService) {

        this.dashScopeChatClient = chatClient;
        this.weatherService = weatherService;
    }

    /**
     * No Tool
     */
    @GetMapping("/chat")
    public String simpleChat(@RequestParam(value = "query", defaultValue = "请告诉我北京1天以后的天气") String query) {

        return dashScopeChatClient.prompt(query).call().content();
    }

    /**
     * Function as Tools - FunctionCallBack
     */
    // Note 2: ★ FunctionToolCallback.builder() 是把任意 Service (Function) 包装成 Tool 的核心 API。
    //
    // 四个核心配置 (前两个是必需的):
    //   name:        LLM 看到的工具名, 后续会出现在 LLM 的「工具调用决策」里, 必须合法 (字母/数字/下划线)
    //   function:    真正干活的 Service, 必须是 java.util.function.Function<I, O> 的实现
    //   description: ★ 告诉 LLM 这个工具干啥的, 决定 LLM 是否调用、如何调用
    //   inputType:   ★ 输入参数类型, Spring AI 用它自动生成 JSON Schema 给 LLM 看
    //
    // 对比方式 1 (TimeController):
    //   方式 1: TimeTools 类有 @Tool, .tools(timeTools) 自动反射所有方法
    //   方式 2: 直接拿 WeatherService 包装, 名字/描述/入参类型在 builder 里手动配置
    // 方式 2 更适合「不想改 Service 源码, 只想临时挂载」的场景。
    @GetMapping("/chat-tool-function-name")
    public String chatWithWeatherFunction(@RequestParam(value = "query", defaultValue = "请告诉我北京1天以后的天气") String query) {

        return dashScopeChatClient.prompt(query).toolCallbacks(
                FunctionToolCallback.builder("getWeather", weatherService)
                        .description("Use api.weather to get weather information.")
                        .inputType(WeatherService.Request.class)
                        .build()
        ).call().content();
    }

}
