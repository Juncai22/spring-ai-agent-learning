package com.pi.agent.types;

/**
 * 工具执行过程中报告中间进度更新的回调接口。
 *
 * <p>实现类会接收工具执行过程中产生的部分 {@link AgentToolResult} 实例。
 * Agent 主循环将每次调用转换为 {@code tool_execution_update} 事件，
 * 以便订阅者能够在 UI 中实时渲染执行进度。
 *
 * @see AgentToolResult
 */
@FunctionalInterface
public interface AgentToolUpdateCallback {

    /**
     * 在工具执行过程中，使用产生的部分结果调用此方法。
     *
     * @param partialResult 工具的中间执行结果
     */
    void onUpdate(AgentToolResult<?> partialResult);
}