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

package com.alibaba.cloud.ai.example.llm.auditor;

// Note 1: Spring Boot 启动类, llm-auditor 模块入口。
// 启动时:
//   1. 自动装配 ChatModel (DashScope/OpenAI starter 提供)
//   2. 注入 Tavily API Key (从 yml 的 search.tavily.api-key 读)
//   3. 启动 Tomcat, 暴露 LLMAuditorController 的 /ai/agent 接口
//
// 访问: GET http://localhost:8080/ai/agent?query=中国的首都是哪里
// 会触发: critic 审查(联网搜索) → reviser 修订 → 返回审查结论 + 修订答案
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author : zhengyuchao
 * @date : 2026/1/22
 */
// Note 2: @SpringBootApplication = 配置 + 自动装配 + 组件扫描 三合一。
// 启动后访问 /ai/agent 触发 Reflection 多 Agent 流程。
@SpringBootApplication
public class LLMAuditorApplication {
    public static void main(String[] args) {
        SpringApplication.run(LLMAuditorApplication.class, args);
    }
}
