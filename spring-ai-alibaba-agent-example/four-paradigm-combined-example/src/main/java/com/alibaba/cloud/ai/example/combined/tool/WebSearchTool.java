package com.alibaba.cloud.ai.example.combined.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * WebSearchTool —— 联网搜索工具（并行分支 1）。
 *
 * 在 research_agent 内部, LLM 会同时调 web_search 和 knowledge_base 两个工具,
 * 这就是「单 Agent 内的并行」——一次 LLM 决策返回多个 tool_calls, 框架并行执行。
 * (区别于第8站 graph 的「跨节点并行 fan-out/fan-in」, 这里是 ReAct 内的轻量并行)
 *
 * 本例用 mock 数据, 避免依赖外部 API key。
 */
public class WebSearchTool implements BiFunction<Map<String, Object>, ToolContext, String> {

    @Override
    public String apply(Map<String, Object> args, ToolContext toolContext) {
        String query = (String) args.getOrDefault("query", "Spring AI");
        // mock: 模拟联网搜索结果
        return "[Web搜索] 关于 '" + query + "' 的最新信息: Spring AI 是 Spring 官方的 AI 应用框架, "
                + "提供 ChatClient/ChatModel 抽象, 支持 DashScope/OpenAI 等多家模型。";
    }

    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("web_search", this)
                .description("联网搜索工具, 查询互联网上的最新信息、技术文档、新闻等。")
                .inputType(Map.class)
                .build();
    }
}
