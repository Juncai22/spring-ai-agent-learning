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
package com.alibaba.cloud.ai.toolcall.component;

// Note 1: 本类演示「多参数 Tool」—— 一个工具接收 3 个参数,演示 LLM 如何从用户问题里抽取多个字段。
// 与 TimeTools (单参数) 对比,本类展示的关键能力:
//   - 多个 @ToolParam 如何被 LLM 一次性填好
//   - 参数描述越具体,LLM 抽取越准
//   - 工具内部可以做参数校验 (IllegalArgumentException)
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class CampusScheduleTools {

    // Note 2: description 写得相当具体——「Create a concise campus activity schedule after considering the user's request」。
    // "after considering the user's request" 这句很关键,告诉 LLM:
    //   这个工具不是简单接收数据,而是要「理解用户意图后再生成」。
    // LLM 拿到这个描述,会更倾向于先解析用户输入的关键信息,再调用此工具。
    @Tool(description = "Create a concise campus activity schedule after considering the user's request.")
    public String createCampusSchedule(
            // Note 3: 三个参数都加了详细 description + 示例。
            // "Recommended start time, such as 14:00" —— 示例比纯文字描述有效得多。
            // LLM 看到「such as 14:00」会模仿这个格式,自然输出 "14:00" 而非 "下午两点"。
            @ToolParam(description = "Campus activity or study goal.") String activity,
            @ToolParam(description = "Recommended start time, such as 14:00.") String startTime,
            @ToolParam(description = "Duration in minutes.") int durationMinutes) {

        // Note 4: Tool 内部可以做参数校验。LLM 抽取的参数可能不靠谱 (比如把"2小时"误抽成 0),
        // 业务校验放 Tool 里兜底,比信任 LLM 准。
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }

        // Note 5: 返回值是结构化字符串,LLM 会原样使用或综合到回答里。
        // 生产环境可以返回 JSON 或更复杂的对象 (需要用 @ToolReturn 包装,稍后讲)。
        return "Campus schedule: activity=" + activity + ", startTime=" + startTime
                + ", durationMinutes=" + durationMinutes + ".";
    }

}
