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
// Note 1: AvailableTimeInfo 是 AvailableTimeSlotsTool 的入参结构。
// 三个字段: date (日期), durationMinutes (时长分钟), attendees (参与者)。
// LLM 查可用时段时, 用 date 等查询某天有哪些空闲时间。
public class AvailableTimeInfo {
    /**
     * 日期信息，表示可用时间的具体日期
     */
    private String date;
    
    /**
     * 持续时间（分钟），表示该时间段的长度
     */
    private Integer durationMinutes;
    
    /**
     * 参与者列表，包含所有参与此时间段的人员信息
     */
    private List<String> attendees;

    public List<String> getAttendees() {
        return attendees;
    }

    public void setAttendees(List<String> attendees) {
        this.attendees = attendees;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }
}

