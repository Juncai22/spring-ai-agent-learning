package com.pi.agent.config;

import com.pi.agent.types.AfterToolCallContext;
import com.pi.agent.types.AfterToolCallResult;
import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * 工具执行后的钩子接口（Hook），在工具（Tool）执行完成后被调用。
 *
 * <p>返回的 {@link AfterToolCallResult} 允许对原始工具执行结果进行字段级别的覆盖。
 * 非 null 的字段会替换原始结果中的对应字段，null 字段则保留原始值不变。
 * 这为 Agent 提供了在工具执行后对结果进行二次处理的能力，例如：
 * <ul>
 *   <li>对敏感结果进行脱敏处理</li>
 *   <li>对错误结果进行格式化或包装</li>
 *   <li>记录工具执行日志或审计信息</li>
 *   <li>修改工具返回的内容以适配后续流程</li>
 * </ul>
 *
 * <p><b>验证的需求：13.11</b>
 *
 * @see AfterToolCallContext 工具执行后的上下文信息
 * @see AfterToolCallResult 工具执行后的结果覆盖对象
 */
@FunctionalInterface
public interface AfterToolCallHook {

    /**
     * 对工具执行结果进行后处理。
     * <p>此方法在工具执行完成后、Agent 循环处理结果之前被调用。
     * 实现可以通过返回的 {@link AfterToolCallResult} 对结果进行字段级覆盖。
     *
     * @param context 工具执行后的上下文，包含助理消息、工具调用信息、参数、执行结果、错误标志和 Agent 上下文
     * @param signal  取消信号，用于协程式取消正在进行的操作
     * @return 一个 CompletableFuture，异步解析为钩子执行结果（字段级覆盖）
     */
    CompletableFuture<AfterToolCallResult> call(AfterToolCallContext context, CancellationSignal signal);
}
