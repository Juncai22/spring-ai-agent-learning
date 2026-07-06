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

package com.alibaba.cloud.ai.example.llm.auditor.tool;

// Note 1: WebSearchTool 是 llm-auditor 的「联网搜索工具」——让 critic Agent 能查互联网验证事实。
//
// 为什么需要它: Reflection 范式的核心是「审查答案准确性」, 审查要靠真实信息。
// LLM 自己有知识截止日期, 审查 2026 年的事必须联网。这个工具就是给 critic 用的「核实武器」。
//
// 实现: implements BiFunction<Request, ToolContext, String> —— 双参函数接口。
// 对比第 5 站 WeatherService (implements Function<I,O>): 这里多了 ToolContext 参数。
// 两种都能当 Tool, BiFunction 更灵活 (能拿到调用上下文)。
//
// 搜索引擎: 用 Tavily (专为 AI 设计的搜索 API), 不是 Google/Bing。
// Tavily 会自动总结答案 + 返回清洗后的纯文本片段, 适合 AI 阅读。
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * @author : zhengyuchao
 * @date : 2026/1/22
 */
public class WebSearchTool implements BiFunction<WebSearchTool.Request, ToolContext, String> {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);


    private final String tavilyApiKey;

    // Note 2: Tavily 搜索 API 地址。Tavily 是专为 AI Agent 设计的搜索引擎,
    // 返回结构化结果 (title/url/content) + 可选的 AI 摘要, 比 Google更适合 LLM 阅读。
    private static final String TAVILY_URL = "https://api.tavily.com/search";

    // Note 3: RestTemplate 是 Spring 的同步 HTTP 客户端 (阻塞式)。
    // 工具调用是同步的 (LLM 等结果), 所以用 RestTemplate 而非 WebClient。
    private final RestTemplate restTemplate;

    public WebSearchTool(String tavilyApiKey) {
        this.restTemplate = new RestTemplate();
        this.tavilyApiKey = tavilyApiKey;
    }

    // Note 4: ★ 工具主逻辑。LLM 决定调 web_search 时, 框架调这个方法。
    @Override
    public String apply(Request request, ToolContext toolContext) {
        log.info("🔍 Tavily Searching for: {}", request.query);

        try {
            // 1. 构建请求头
            // Note 5: 标准 JSON 请求头。Tavily 要求 Content-Type: application/json。
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 2. 构建请求体
            // include_answer: 让 Tavily 生成一段简短的回答
            // search_depth: "basic" (快) 或 "advanced" (深，但耗额度)
            // Note 6: 请求体参数:
            //   api_key:       鉴权
            //   query:         搜索关键词 (LLM 传的)
            //   search_depth:  basic 快 / advanced 深 (耗额度)
            //   include_answer: 让 Tavily 生成 AI 摘要 (LLM 直接读摘要就够)
            //   max_results:   返回几条结果 (默认 5)
            Map<String, Object> body = Map.of(
                    "api_key", tavilyApiKey,
                    "query", request.query,
                    "search_depth", "basic",
                    "include_answer", true,
                    "max_results", request.maxResults != null ? request.maxResults : 5
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            // 3. 发送 POST 请求
            // Note 7: restTemplate.postForObject 同步调 Tavily, 自动反序列化成 TavilyResponse。
            TavilyResponse response = restTemplate.postForObject(TAVILY_URL, entity, TavilyResponse.class);

            // 4. 处理并格式化结果给 AI
            // Note 8: 空结果兜底, 返回文本让 LLM 自己判断。
            if (response == null || response.results == null || response.results.isEmpty()) {
                return "未找到关于 '" + request.query + "' 的相关信息。";
            }

            StringBuilder output = new StringBuilder();

            // 如果 Tavily 生成了直接回答，优先放入
            // Note 9: AI 摘要放最前面, LLM 优先读摘要快速判断。
            if (response.answer != null && !response.answer.isEmpty()) {
                output.append("【AI 摘要】: ").append(response.answer).append("\n\n");
            }

            // Note 10: ★ 详细来源格式化。这个格式很重要——CriticAgentHook 会解析它提取引用!
            // 格式:
            //   【详细来源】:
            //   1. 标题
            //      内容: xxx
            //      链接: url
            output.append("【详细来源】:\n");
            for (int i = 0; i < response.results.size(); i++) {
                TavilyResult result = response.results.get(i);
                output.append(i + 1).append(". ").append(result.title).append("\n");
                output.append("   内容: ").append(result.content).append("\n");
                output.append("   链接: ").append(result.url).append("\n\n");
            }

            String finalResult = output.toString();
            // log.info("Search Result: {}", finalResult); // 调试时可以打开
            return finalResult;

        } catch (Exception e) {
            // Note 11: 异常转文本, 不抛。让 LLM 收到「搜索失败」自己判断怎么办。
            log.error("Tavily search failed", e);
            return "搜索服务异常: " + e.getMessage();
        }
    }

    // Note 12: ★ 静态工厂方法: 把 WebSearchTool 包装成 FunctionToolCallback。
    // 给 critic Agent 挂载时用: .tools(WebSearchTool.getFunctionToolCallback(tavilyApiKey))
    // 这是你第 5 站学的「方式 ② FunctionToolCallback」——编程式包装 BiFunction。
    public static FunctionToolCallback getFunctionToolCallback(String tavilyApiKey) {
        return FunctionToolCallback.builder("web_search", new WebSearchTool(tavilyApiKey))
                .description("联网搜索工具。用于查询实时新闻、具体事实、游戏攻略或现有知识库中没有的信息。")
                .inputType(Request.class)
                .build();
    }

    // --- DTO 类定义 ---

    // Note 13: Request record —— 入参 schema。LLM 据此填 query 和 maxResults。
    // @JsonPropertyDescription 告诉 LLM 字段含义, LLM 据此决定传什么值。
    @JsonClassDescription("搜索请求参数")
    public record Request(
            @JsonProperty(value = "query", required = true)
            @JsonPropertyDescription("搜索关键词")
            String query,

            @JsonProperty(value = "max_results")
            @JsonPropertyDescription("结果数量，默认5")
            Integer maxResults
    ) {}

    // Note 14: TavilyResponse —— Tavily API 返回的响应结构。
    // @JsonIgnoreProperties(ignoreUnknown = true) 忽略未声明的字段, 避免反序列化失败。
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TavilyResponse {
        @JsonProperty("answer")
        public String answer; // Tavily 自动总结的答案

        @JsonProperty("results")
        public List<TavilyResult> results;
    }

    // Note 15: TavilyResult —— 单条搜索结果。content 是清洗过的纯文本, 适合 AI 阅读。
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TavilyResult {
        @JsonProperty("title")
        public String title;

        @JsonProperty("url")
        public String url;

        @JsonProperty("content")
        public String content; // 这是一个经过清洗的纯文本片段，非常适合 AI
    }
}
