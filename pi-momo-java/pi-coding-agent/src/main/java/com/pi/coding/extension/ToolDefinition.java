package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.ai.core.types.CancellationSignal;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 工具定义 —— 用于 registerTool() 注册可供 LLM 调用的工具。
 *
 * <p>定义了一个 LLM 可以调用的工具，包括：
 * <ul>
 *   <li>名称和标签：供 LLM 和 UI 使用</li>
 *   <li>描述：LLM 理解工具用途的说明文本</li>
 *   <li>提示词片段和指南：优化 LLM 对工具的理解和使用</li>
 *   <li>参数模式：JSON Schema 格式的参数定义</li>
 *   <li>执行器：工具的实际执行逻辑</li>
 * </ul>
 *
 * @param name             工具名称（在 LLM 工具调用中使用）
 * @param label            面向 UI 的可读标签
 * @param description      面向 LLM 的工具描述
 * @param promptSnippet    可选的单行提示词片段，用于 "可用工具" 部分
 * @param promptGuidelines 可选的指南列表，用于 "指南" 部分
 * @param parameters       参数模式（JSON Schema 格式）
 * @param executor         工具执行器函数
 * @param <TDetails>       工具结果中特定详情的类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition<TDetails>(
    @JsonProperty("name") String name,
    @JsonProperty("label") String label,
    @JsonProperty("description") String description,
    @JsonProperty("promptSnippet") String promptSnippet,
    @JsonProperty("promptGuidelines") List<String> promptGuidelines,
    @JsonProperty("parameters") JsonNode parameters,
    ToolExecutor<TDetails> executor
) {

    /**
     * 工具执行的函数式接口。
     *
     * @param <TDetails> 工具结果中特定详情的类型
     */
    @FunctionalInterface
    public interface ToolExecutor<TDetails> {
        /**
         * 执行工具逻辑。
         *
         * <p>工具执行器接收已验证的参数，执行工具逻辑，并返回结果。
         * 支持：
         * <ul>
         *   <li>取消信号：通过 {@link CancellationSignal} 检查是否被取消</li>
         *   <li>流式更新：通过 {@link AgentToolUpdateCallback} 提供部分结果</li>
         *   <li>异步执行：通过 {@link CompletableFuture} 返回结果</li>
         * </ul>
         *
         * @param toolCallId 工具调用的唯一标识符
         * @param params     已验证的工具参数（JSON 格式）
         * @param signal     取消信号（可为 null）
         * @param onUpdate   流式更新回调（可为 null）
         * @param context    扩展上下文
         * @return 一个 CompletableFuture，完成时包含工具执行结果
         */
        CompletableFuture<AgentToolResult<TDetails>> execute(
            String toolCallId,
            JsonNode params,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate,
            ExtensionContext context
        );
    }

    /**
     * ToolDefinition 的构建器。
     *
     * @param <TDetails> 工具结果中特定详情的类型
     */
    public static class Builder<TDetails> {
        private String name;
        private String label;
        private String description;
        private String promptSnippet;
        private List<String> promptGuidelines;
        private JsonNode parameters;
        private ToolExecutor<TDetails> executor;

        public Builder<TDetails> name(String name) { this.name = name; return this; }

        public Builder<TDetails> label(String label) { this.label = label; return this; }

        public Builder<TDetails> description(String description) { this.description = description; return this; }

        public Builder<TDetails> promptSnippet(String promptSnippet) { this.promptSnippet = promptSnippet; return this; }

        public Builder<TDetails> promptGuidelines(List<String> promptGuidelines) { this.promptGuidelines = promptGuidelines; return this; }

        public Builder<TDetails> parameters(JsonNode parameters) { this.parameters = parameters; return this; }

        public Builder<TDetails> executor(ToolExecutor<TDetails> executor) { this.executor = executor; return this; }

        public ToolDefinition<TDetails> build() {
            return new ToolDefinition<>(name, label, description, promptSnippet, promptGuidelines, parameters, executor);
        }
    }

    public static <TDetails> Builder<TDetails> builder() {
        return new Builder<>();
    }
}
