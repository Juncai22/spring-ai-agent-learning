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

// Note 1: 本 Controller 是 tool-calling 模块的「综合案例」—— 一次请求挂 3 个 Tool, 让 LLM 自主决定调哪些。
//
// 三个工具:
//   TimeTools:              查时间 (@Tool 注解方式)
//   CampusScheduleTools:    排日程 (@Tool 注解方式)
//   WeatherService:         查天气 (FunctionToolCallback 方式)
//
// 典型用户问: "请查询上海当前时间和天气, 并为我安排一小时的校园跑步计划"
// LLM 的决策可能是:
//   1. 调 getCityTime("Asia/Shanghai")  → 拿到 "14:00"
//   2. 调 getWeather(上海)             → 拿到 "晴, 25度"
//   3. 调 createCampusSchedule("校园跑步", "14:00", 60) → 拿到排程
//   4. 综合三者, 生成自然语言回答
//
// 这是「多步推理 + 多工具调用」的真实场景, 也是 LLM 智能体的雏形。
import com.alibaba.cloud.ai.toolcall.component.CampusScheduleTools;
import com.alibaba.cloud.ai.toolcall.component.TimeTools;
import com.alibaba.cloud.ai.toolcalling.weather.WeatherService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/campus")
public class CampusAssistantController {

    private final ChatClient dashScopeChatClient;

    private final TimeTools timeTools;

    private final CampusScheduleTools campusScheduleTools;

    private final WeatherService weatherService;

    public CampusAssistantController(ChatClient chatClient, TimeTools timeTools,
            CampusScheduleTools campusScheduleTools, WeatherService weatherService) {

        this.dashScopeChatClient = chatClient;
        this.timeTools = timeTools;
        this.campusScheduleTools = campusScheduleTools;
        this.weatherService = weatherService;
    }

    /**
     * Combine annotated method tools and a function callback in one request.
     */
    // Note 2: ★★ 核心 API 组合——同一请求同时挂 @Tool 方法和 FunctionToolCallback。
    //
    // 调用链:
    //   .tools(timeTools, campusScheduleTools)
    //     └── 把两个 Tools 类的所有 @Tool 方法批量挂上 (TimeTools.getCityTime + CampusScheduleTools.createCampusSchedule)
    //   .toolCallbacks(FunctionToolCallback.builder("getWeather", weatherService)...)
    //     └── 再挂一个编程式 Tool (WeatherService)
    //
    // 关键观察: 两种方式可以混用! Spring AI 内部统一把两种 Tool 合并成「工具清单」发给 LLM,
    // LLM 看到的是同一个平铺的工具列表, 不区分挂载方式。
    //
    // 实际效果: LLM 可以自主决定「调哪几个、顺序、并行」。
    @GetMapping("/chat-tools")
    public String chatWithCampusTools(@RequestParam(value = "query",
            defaultValue = "请查询上海当前时间和天气，并为我安排一小时的校园跑步计划") String query) {

        return dashScopeChatClient.prompt(query)
                // Note 3: 批量挂载两个 @Tool 类。每个类里的所有 @Tool 方法都会被自动反射挂上。
                .tools(timeTools, campusScheduleTools)
                // Note 4: 再挂一个 FunctionToolCallback (WeatherService)。
                // 这里复用 WeatherController 里的写法: 名字/描述/入参类型都在 builder 里写。
                // 这种「按需临时挂」的写法很适合运行时动态配置。
                .toolCallbacks(FunctionToolCallback.builder("getWeather", weatherService)
                        .description("Use api.weather to get weather information.")
                        .inputType(WeatherService.Request.class)
                        .build())
                .call()
                .content();
    }

}
