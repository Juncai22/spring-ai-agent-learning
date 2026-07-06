package com.alibaba.cloud.ai.example.combined.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * KnowledgeBaseTool —— 知识库查询工具（并行分支 2）。
 *
 * 与 WebSearchTool 配合: research_agent 会同时调这两个工具,
 * 一个查互联网, 一个查本地知识库, 结果汇总给 writer_agent。
 *
 * 本例用 mock 数据, 模拟从向量数据库检索文档。
 */
public class KnowledgeBaseTool implements BiFunction<Map<String, Object>, ToolContext, String> {

    @Override
    public String apply(Map<String, Object> args, ToolContext toolContext) {
        String query = (String) args.getOrDefault("query", "Spring AI");
        // mock: 模拟知识库检索结果
        return "[知识库] 关于 '" + query + "' 的内部文档: 项目已集成 Spring AI Alibaba, "
                + "使用 DashScope 模型, 已实现 chat/tool-calling/structured-output 等功能。";
    }

    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("knowledge_search", this)
                .description("知识库检索工具, 查询本地向量数据库中的内部文档、项目资料等。")
                .inputType(Map.class)
                .build();
    }
}
