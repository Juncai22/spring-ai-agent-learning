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

import com.cloud.alibaba.ai.example.agent.model.CalendarInfo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.function.BiFunction;

/**
 * A tool for creating calendar events.
 * This class implements BiFunction to process CalendarInfo and ToolContext,
 * validating input data and simulating event creation.
 *
 * @author wangjx
 * @since 2026-02-13
 */
// Note 1: CreateCalendarEventTool 是「创建日历事件」工具——给 calendar Agent 用的。
// calendar Agent 查完可用时段后, 调它真正创建事件。
//
// implements BiFunction<CalendarInfo, ToolContext, String>:
//   入参 CalendarInfo (title/startTime/endTime/attendees)
//   返回 String (创建结果)
public class CreateCalendarEventTool implements BiFunction<CalendarInfo, ToolContext, String> {


    @Override
    public String apply(CalendarInfo calendarInfo, ToolContext toolContext) {
        // Parse parameters
        String title = calendarInfo.getTitle();
        String startTime = calendarInfo.getStartTime();
        String endTime = calendarInfo.getEndTime();
        List<String> attendees = calendarInfo.getAttendees();

        // Note 2: ★ 校验时间格式——必须是 ISO 8601 datetime (yyyy-MM-ddTHH:mm:ss)。
        // 注意带 T (如 2026-07-06T14:00:00), 区别于 AvailableTimeSlotsTool 的纯日期。
        if (!isValidIsoDateTime(startTime) || !isValidIsoDateTime(endTime)) {
            return "Error: Invalid ISO datetime format";
        }

        // Simulate event creation
        // Note 3: mock 创建——返回创建成功信息。attendees 可能为 null, 用三元运算兜底。
        return String.format("Event created: %s from %s to %s with %d attendees",
                title, startTime, endTime, attendees==null ? 0:attendees.size());
    }


    private boolean isValidIsoDateTime(String datetime) {
        // Simple validation of ISO 8601 format (should use stricter validation in production)
        return datetime != null && datetime.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }
    // Note 4: 工具名 "create_calendar_event"。
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("create_calendar_event", this)
                .description("create_calendar_event")
                .inputType(CalendarInfo.class)
                .build();
    }
}
