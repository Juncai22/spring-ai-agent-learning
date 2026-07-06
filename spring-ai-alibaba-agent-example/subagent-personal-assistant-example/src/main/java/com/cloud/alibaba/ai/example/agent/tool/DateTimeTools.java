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

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Utility class for handling date and time operations.
 * Provides functionality to retrieve the current date and time.
 *
 * @author wangjx
 * @since 2026-02-13
 */
// Note 1: DateTimeTools 是「获取当前时间」工具——给 calendar Agent 用的。
// 当用户说「下周二开会」, calendar Agent 需要知道「今天」才能算出下周二是几号。
//
// implements BiFunction<Map<String,Object>, ToolContext, String>:
//   入参 Map (无具体字段, 因为查时间不需要参数)
//   返回 String (格式化后的时间文本)
// 这是项目自定义的 Tool 接口模式 (第6站 react-agent 学过), 区别于 @Tool 注解。
public class DateTimeTools implements BiFunction<Map<String, Object>, ToolContext, String> {


    @Override
    public String apply(Map<String, Object> map, ToolContext toolContext) {
        // Note 2: 返回当前时间, 格式 "2026-07-06 14：30：00"。
        // 注意用了中文冒号： (全角), 可能是笔误, 但不影响功能。
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH：mm：ss"));
    }

    // Note 3: ★ toolCallback() 把自己包装成 Spring AI 的 ToolCallback。
    // 工具名 "get_current_date_time", description 同名 (简化写法, 生产应写清楚用途)。
    // inputType(Map.class) 告诉框架入参是 Map (无具体 schema)。
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("get_current_date_time", this)
                .description("get_current_date_time")
                .inputType(Map.class)
                .build();
    }
}
