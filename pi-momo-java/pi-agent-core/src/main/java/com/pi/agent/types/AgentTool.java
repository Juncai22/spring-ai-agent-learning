package com.pi.agent.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Tool;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 层工具定义，扩展 pi-ai-java 的 {@link Tool} record 的所有字段，
 * 增加 {@link #label()} 用于 UI 展示和 {@link #execute} 方法用于实际执行。
 *
 * <p>实现者需要提供 {@code name}、{@code description}、{@code parameters} 和
 * {@code execute} 方法。{@code label} 默认返回 {@code name}，可按需覆盖。
 *
 * <p>提供 {@link #toTool()} 默认方法，将 AgentTool 转换为 pi-ai-java 的
 * {@link Tool} record，用于构建 LLM 上下文。
 *
 * @see Tool
 * @see AgentToolResult
 * @see AgentToolUpdateCallback
 */
public interface AgentTool {

    /**
     * 工具名称，用于 LLM 调用时的工具标识。
     *
     * @return 工具名称
     */
    String name();

    /**
     * 工具描述，帮助 LLM 理解工具的用途和适用场景。
     *
     * @return 工具描述
     */
    String description();

    /**
     * 工具参数的 JSON Schema 定义，用于 LLM 生成符合规范的工具调用参数。
     *
     * @return JSON Schema 格式的参数定义
     */
    JsonNode parameters();

    /**
     * 工具的 UI 展示标签，默认返回 {@link #name()}。
     * 实现类可覆盖此方法以提供更适合 UI 展示的友好名称。
     *
     * @return UI 展示标签
     */
    default String label() {
        return name();
    }

    /**
     * 执行工具逻辑。
     * <p>实现应支持异步执行，并定期检查取消信号以支持操作取消。
     *
     * @param toolCallId 工具调用 ID，用于关联请求和响应
     * @param args       工具参数，由 LLM 生成并经过校验的 JSON 节点
     * @param signal     取消信号，工具实现应定期检查以支持取消操作
     * @param onUpdate   进度更新回调，工具可在执行过程中通过此回调报告中间结果
     * @return 异步工具执行结果，包含 {@link AgentToolResult}
     */
    CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate
    );

    /**
     * 将 AgentTool 转换为 pi-ai-java 的 {@link Tool} record，
     * 用于构建 LLM 上下文中的工具定义。
     *
     * @return 对应的 Tool record 实例
     */
    default Tool toTool() {
        return new Tool(name(), description(), parameters());
    }
}