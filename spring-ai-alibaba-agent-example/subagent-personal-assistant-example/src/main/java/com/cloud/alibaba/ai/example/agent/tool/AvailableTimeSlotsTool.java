/*
 * Copyright 2026-2027 the original author or authors.
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
package com.cloud.alibaba.ai.example.agent.tool;

import com.cloud.alibaba.ai.example.agent.model.AvailableTimeInfo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.function.BiFunction;

/**
 * A tool that retrieves available time slots for scheduling meetings.
 * This tool implements the BiFunction interface to process input parameters
 * and return formatted time slot information.
 *
 * @author wangjx
 * @since 2026-02-13
 */
// Note 1: AvailableTimeSlotsTool 是「查询可用时段」工具——给 calendar Agent 用的。
// 用户说「下周二开会」, calendar Agent 调它查下周二有哪些空闲时段 (09:00/14:00/16:00)。
//
// implements BiFunction<AvailableTimeInfo, ToolContext, String>:
//   入参 AvailableTimeInfo (含 date)
//   返回 String (可用时段列表)
public class AvailableTimeSlotsTool implements BiFunction<AvailableTimeInfo, ToolContext, String> {


    @Override
    public String apply(AvailableTimeInfo args, ToolContext toolContext) {
        // Parse input parameters
        String date =args.getDate();

        // Note 2: ★ 参数校验——检查日期格式是否是 ISO 8601 (yyyy-MM-dd)。
        // LLM 可能传错格式 (如 "下周二"), 这里拦住返回错误, 让 LLM 重试。
        if (!isValidIsoDate(date)) {
            return "Error: Invalid ISO date format";
        }

        // Simulate querying available time slots
        // Note 3: mock 数据——固定返回 09:00/14:00/16:00 三个时段。
        // 生产应查真实日历系统的空闲时段。
        List<String> timeSlots = List.of("09:00", "14:00", "16:00");
        return String.format("Available time slots for %s: %s", date, String.join(", ", timeSlots));
    }


    // Note 4: 简单正则校验 ISO 日期格式。\\d{4}-\\d{2}-\\d{2} = yyyy-MM-dd。
    private boolean isValidIsoDate(String date) {
        // Simple validation of ISO 8601 date format
        return date != null && date.matches("\\d{4}-\\d{2}-\\d{2}");
    }


    // Note 5: 工具名 "get_available_time_slots"。
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("get_available_time_slots", this)
                .description("get_available_time_slots")
                .inputType(AvailableTimeInfo.class)
                .build();
    }
}
