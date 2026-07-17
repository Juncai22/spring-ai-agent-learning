package com.pi.agent.config;

import com.pi.agent.types.BeforeToolCallContext;
import com.pi.agent.types.BeforeToolCallResult;
import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * 工具执行前的钩子接口（Hook），在工具（Tool）执行之前被调用。
 *
 * <p>当返回的 {@link BeforeToolCallResult} 中 {@code block == true} 时，
 * Agent 主循环会阻止该工具执行，并生成一个包含 {@code reason}（或默认消息）
 * 的错误工具结果返回给 LLM。这为 Agent 提供了对工具调用进行前置拦截的能力，
 * 典型应用场景包括：
 * <ul>
 *   <li>安全校验：检查工具调用是否在白名单中</li>
 *   <li>权限控制：验证当前上下文是否有权执行该工具</li>
 *   <li>参数校验：检查工具参数是否合法或存在安全风险</li>
 *   <li>速率限制：控制工具调用的频率，防止滥用</li>
 *   <li>审计日志：记录所有工具调用请求</li>
 * </ul>
 *
 * <p><b>验证的需求：13.11</b>
 *
 * @see BeforeToolCallContext 工具执行前的上下文信息
 * @see BeforeToolCallResult 工具执行前的结果，包含是否拦截及拦截原因
 */
@FunctionalInterface
public interface BeforeToolCallHook {

    /**
     * 评估工具调用是否应该继续执行。
     * <p>此方法在工具执行之前被调用，实现可以通过返回结果中的 {@code block} 字段
     * 决定是否放行或拦截该工具调用。
     *
     * @param context 工具执行前的上下文，包含助理消息、工具调用信息、参数和 Agent 上下文
     * @param signal  取消信号，用于协程式取消正在进行的操作
     * @return 一个 CompletableFuture，异步解析为钩子执行结果（block 表示拦截，allow 表示放行）
     */
    CompletableFuture<BeforeToolCallResult> call(BeforeToolCallContext context, CancellationSignal signal);
}
