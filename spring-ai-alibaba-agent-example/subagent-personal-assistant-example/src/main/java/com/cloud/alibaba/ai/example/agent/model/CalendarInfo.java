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

package com.cloud.alibaba.ai.example.agent.model;

import java.util.List;

/**
 *
 * @author wangjx
 * @since 2026-02-13
 * */
// Note 1: CalendarInfo 是 CreateCalendarEventTool 的入参结构。
// 四个字段: title (标题), startTime/endTime (起止时间), attendees (参与者列表)。
// LLM 创建日历事件时, 会从用户自然语言提取这些字段填进来。
public class CalendarInfo {
    /**
     * 日历事件的标题
     */
    private String title;
    
    /**
     * 日历事件的开始时间
     */
    private String startTime;
    
    /**
     * 日历事件的结束时间
     */
    private String endTime;
    
    /**
     * 日历事件的参与者列表
     */
    private List<String> attendees;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public List<String> getAttendees() {
        return attendees;
    }

    public void setAttendees(List<String> attendees) {
        this.attendees = attendees;
    }
}
