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

package com.alibaba.cloud.ai.example.llm.auditor.hook;

// Note 1: ★ CriticAgentHook 是 critic Agent 的「模型钩子」——在 critic 调 LLM 前后插入逻辑。
//
// 这是你学的第三种「钩子」机制, 横向对比:
//   Advisor (第2站):        拦截「ChatClient 调 LLM」层面, before/after 改请求/响应
//   ToolInterceptor (第6站): 拦截「工具调用」层面, interceptToolCall 改工具执行
//   ModelHook (本站):        拦截「Agent 内部调 LLM」层面, beforeModel/afterModel
//   HumanInTheLoopHook (第6站): 也是 ModelHook 的一种, 用于审批暂停
//
// 三者层级不同, 但本质都是「洋葱模型」——请求前/响应后插入处理。
//
// 本 Hook 的具体职责:
//   critic Agent 调 LLM 审查答案时, 会调 web_search 工具查证。
//   afterModel: 把搜索结果的「引用来源」提取出来, 追加到 critic 的输出后面。
//   这样 critic 的最终输出 = 审查结论 + 引用列表, 让用户能看到证据来源。
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author : zhengyuchao
 * @date : 2026/1/22
 */
// Note 2: extends ModelHook —— 模型钩子抽象类。重写 beforeModel/afterModel 两个方法。
public class CriticAgentHook extends ModelHook {

    private static final Logger log = LoggerFactory.getLogger(CriticAgentHook.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "";
    }

    // Note 3: beforeModel —— critic 调 LLM 前。本例不需要改请求, 直接返回空 Map。
    // 如果要做 RAG, 就在这里检索知识库, 拼进 prompt。
    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }

    // Note 4: ★ 核心方法: afterModel —— critic 调完 LLM 后, 把搜索引用追加到输出。
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        // ① 取 critic 的输出 (outputKey="critic_agent_output", 在 Controller 里配的)
        Optional<Object> messagesOpt = state.value("critic_agent_output");

        if (!messagesOpt.isPresent()) {
            return CompletableFuture.completedFuture(Map.of());  // 没输出, 不处理
        }

        if (!(messagesOpt.get() instanceof AssistantMessage)) {
            return CompletableFuture.completedFuture(Map.of());  // 不是 AI 消息, 不处理
        }

        AssistantMessage message = (AssistantMessage) messagesOpt.get();

        // ② ★ 从 state 的 messages 中提取工具响应中的引用信息
        // critic 调 web_search 后, 搜索结果存在 state.messages 里 (TOOL 类型消息)
        List<String> references = extractReferencesFromState(state);

        if (references.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());  // 没引用, 不处理
        }

        // ③ 构建引用文本
        // Note 5: 拼接引用列表, 格式:
        //   Reference:
        //   * [标题](url): 内容
        //   * [标题](url): 内容
        StringBuilder referenceText = new StringBuilder("\n\nReference:\n\n");
        for (String ref : references) {
            referenceText.append(ref);
        }

        // ④ 将引用追加到消息内容
        // Note 6: ★ 改写 critic 的输出: 原文 + 引用列表。
        // 这样 critic 的最终输出 = 审查结论 + 证据来源, 提升可信度。
        String originalText = message.getText();
        String newText = originalText + referenceText.toString();

        // ⑤ 重建 AssistantMessage (保留原 media/metadata/toolCalls, 只改 content)
        AssistantMessage newAssistantMessage = AssistantMessage.builder()
                .content(newText)
                .media(message.getMedia())
                .properties(message.getMetadata())
                .toolCalls(message.getToolCalls())
                .build();

        // ⑥ 返回更新: 把新的 critic_agent_output 写回 state
        return CompletableFuture.completedFuture(Map.of("critic_agent_output", newAssistantMessage));
    }

    /**
     * 从 state 的 messages 中提取工具响应中的引用信息
     * 解析 Tavily 搜索工具返回的结果格式
     */
    // Note 7: ★ 从 state.messages 提取搜索引用。critic 调 web_search 时,
    // 工具返回的搜索结果会作为 TOOL 类型消息存在 state.messages 里。
    // 这个方法遍历 messages, 找到 TOOL 类型, 解析其中的搜索结果。
    private List<String> extractReferencesFromState(OverAllState state) {
        List<String> references = new ArrayList<>();

        try {
            // 从 state 中获取 messages 数组
            Optional<Object> messagesObj = state.value("messages");
            if (!messagesObj.isPresent()) {
                return references;
            }

            List<?> messages;
            if (messagesObj.get() instanceof List) {
                messages = (List<?>) messagesObj.get();
            } else {
                return references;
            }

            // 遍历 messages，找到 TOOL 类型的消息
            // Note 8: messages 列表里有多种类型: USER/ASSISTANT/SYSTEM/TOOL。
            // 只关心 TOOL 类型 (工具返回的结果), 其他跳过。
            for (Object msgObj : messages) {
                if (!(msgObj instanceof Map)) {
                    continue;
                }

                Map<String, Object> msgMap = (Map<String, Object>) msgObj;
                String messageType = getStringValue(msgMap, "messageType");

                // 查找 TOOL 类型的消息
                if ("TOOL".equals(messageType)) {
                    // 获取 responses 数组
                    // Note 9: TOOL 消息里有 responses 数组, 每个含 responseData (搜索结果文本)。
                    Object responsesObj = msgMap.get("responses");
                    if (responsesObj == null) {
                        continue;
                    }

                    List<?> responses;
                    if (responsesObj instanceof List) {
                        responses = (List<?>) responsesObj;
                    } else {
                        continue;
                    }

                    // 遍历每个 response，提取 responseData
                    for (Object respObj : responses) {
                        if (!(respObj instanceof Map)) {
                            continue;
                        }

                        Map<String, Object> respMap = (Map<String, Object>) respObj;
                        String responseData = getStringValue(respMap, "responseData");

                        if (responseData != null && !responseData.isEmpty()) {
                            // 解析 Tavily 返回的搜索结果格式
                            // Note 10: responseData 就是 WebSearchTool.apply() 返回的那个格式化字符串:
                            //   【AI 摘要】: ...
                            //   【详细来源】:
                            //   1. title
                            //      内容: xxx
                            //      链接: url
                            List<String> refs = parseTavilySearchResults(responseData);
                            references.addAll(refs);
                        }
                    }
                }
            }

        } catch (Exception e) {
            // Note 11: 异常只记日志, 不抛。Hook 失败不应影响主流程。
            log.warn("Failed to extract references from state messages: {}", e.getMessage());
            log.debug("State content: {}", state, e);
        }

        return references;
    }

    /**
     * 解析 Tavily 搜索工具返回的结果格式
     * 格式示例：
     * 【AI 摘要】: ...
     *
     * 【详细来源】:
     * 1. title
     *    内容: content
     *    链接: url
     */
    // Note 12: ★ 用正则解析 Tavily 搜索结果文本, 提取 title/content/url。
    // 这是个「文本解析」活——因为 WebSearchTool 返回的是格式化字符串, 不是结构化对象。
    private List<String> parseTavilySearchResults(String responseData) {
        List<String> references = new ArrayList<>();

        try {
            // 移除 JSON 字符串的转义字符（如果存在）
            // Note 13: 如果 responseData 被 JSON 转义过 (有 \" 和 \n), 先还原。
            String cleanData = responseData;
            if (cleanData.startsWith("\"") && cleanData.endsWith("\"")) {
                cleanData = cleanData.substring(1, cleanData.length() - 1);
                // 处理转义的换行符和引号
                cleanData = cleanData.replace("\\n", "\n").replace("\\\"", "\"");
            }

            // 查找【详细来源】部分
            // Note 14: 只解析「详细来源」部分, AI 摘要部分跳过 (摘要无 url)。
            int startIndex = cleanData.indexOf("【详细来源】");
            if (startIndex == -1) {
                startIndex = cleanData.indexOf("详细来源");
            }

            if (startIndex == -1) {
                // 如果没有找到标记，尝试从整个字符串中提取
                startIndex = 0;
            } else {
                // 跳过标记行
                int newlineIndex = cleanData.indexOf('\n', startIndex);
                if (newlineIndex != -1) {
                    startIndex = newlineIndex + 1;
                }
            }

            String sourceSection = cleanData.substring(startIndex);

            // 使用正则表达式提取每个搜索结果
            // 匹配格式：数字. title\n   内容: content\n   链接: url
            // 注意：content 可能包含多行，所以使用非贪婪匹配，直到遇到"链接:"
            // Note 15: ★ 正则匹配三段: 序号+标题 / 内容 / 链接。
            //   (\\d+)\\.\\s+([^\\n]+?)  → 序号 + 标题
            //   \\n\\s+内容:\\s+([^\\n]+?)  → 内容
            //   \\n\\s+链接:\\s+([^\\n]+)   → 链接
            Pattern pattern = Pattern.compile(
                "(\\d+)\\.\\s+([^\\n]+?)\\n\\s+内容:\\s+([^\\n]+?)\\n\\s+链接:\\s+([^\\n]+)",
                Pattern.MULTILINE | Pattern.DOTALL
            );

            Matcher matcher = pattern.matcher(sourceSection);

            while (matcher.find()) {
                String title = matcher.group(2).trim();
                String content = matcher.group(3).trim();
                String url = matcher.group(4).trim();

                // 清理 content，移除多余的空白和换行
                content = content.replaceAll("\\s+", " ").trim();

                // 构建引用格式：[title](url): content
                if (!title.isEmpty() && !url.isEmpty()) {
                    // 如果 content 太长，截取前200个字符
                    // Note 16: 引用内容限 200 字, 避免引用列表过长。
                    if (content.length() > 200) {
                        content = content.substring(0, 200) + "...";
                    }
                    String reference = String.format("* [%s](%s): %s\n", title, url, content);
                    references.add(reference);
                }
            }

            // 如果没有匹配到完整格式，尝试更宽松的匹配模式（只有标题和链接）
            // Note 17: 兜底——完整格式没匹配上, 退而求其次只取标题+链接。
            if (references.isEmpty()) {
                Pattern simplePattern = Pattern.compile(
                    "(\\d+)\\.\\s+([^\\n]+?)\\n\\s+链接:\\s+([^\\n]+)",
                    Pattern.MULTILINE
                );

                Matcher simpleMatcher = simplePattern.matcher(sourceSection);
                while (simpleMatcher.find()) {
                    String title = simpleMatcher.group(2).trim();
                    String url = simpleMatcher.group(3).trim();

                    if (!title.isEmpty() && !url.isEmpty()) {
                        String reference = String.format("* [%s](%s)\n", title, url);
                        references.add(reference);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse Tavily search results: {}", e.getMessage());
            log.debug("Response data: {}", responseData, e);
        }

        return references;
    }

    /**
     * 安全地从 Map 中获取字符串值
     */
    // Note 18: 工具方法: 从 Map 安全取字符串, null 返回空串。避免 NPE。
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return "";
        }
        return value.toString();
    }
}
