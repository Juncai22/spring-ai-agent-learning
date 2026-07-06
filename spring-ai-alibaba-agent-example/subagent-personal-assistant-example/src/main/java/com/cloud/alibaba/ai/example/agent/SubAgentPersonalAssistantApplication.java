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
package com.cloud.alibaba.ai.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author wangjx
 * @since 2026-02-13
 */
// Note 1: Spring Boot 启动类, subagent-personal-assistant 模块入口。
// 启动时:
//   1. 自动装配 DashScopeChatModel
//   2. 触发 AgentConfig, 构建 supervisorAgent + calendarAgent + emailAgent
//   3. 启动 Tomcat, 暴露 PersonalAssistantController 的 /react/agent/supervisorAgent 接口
//
// 访问: GET /react/agent/supervisorAgent?query=帮我查张三邮箱并发邮件&threadId=t1
// 会触发: supervisor 调 get_user_email_tool 查邮箱 → 调 email_agent 发邮件 (触发 HITL 暂停)
@SpringBootApplication
public class SubAgentPersonalAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubAgentPersonalAssistantApplication.class, args);
    }
}