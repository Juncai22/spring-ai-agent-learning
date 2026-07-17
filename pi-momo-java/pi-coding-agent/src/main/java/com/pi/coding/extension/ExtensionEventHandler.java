package com.pi.coding.extension;

import java.util.concurrent.CompletableFuture;

/**
 * 扩展事件处理器函数式接口。
 *
 * <p>事件处理器接收事件对象和扩展上下文，并可能返回一个结果来修改 Agent 行为。
 * 处理器是异步的，通过 {@link CompletableFuture} 返回处理结果。
 *
 * <p>根据事件类型的不同，返回的结果类型也不同：
 * <ul>
 *   <li>拦截事件（如 ContextEvent、ToolCallEvent）：返回对应的 {@link EventResult} 子类型</li>
 *   <li>通知事件（如 SessionStartEvent、AgentStartEvent）：返回 null（无需修改行为）</li>
 * </ul>
 *
 * <p>处理器应保证快速的执行，避免长时间阻塞。如果处理器抛出异常，
 * Runner 会捕获异常并通知错误监听器，继续调用下一个处理器。
 *
 * @param <T> 处理器可处理的事件类型
 */
@FunctionalInterface
public interface ExtensionEventHandler<T extends ExtensionEvent> {

    /**
     * 处理一个扩展事件。
     *
     * <p>在此方法中实现对事件的响应逻辑。可以返回一个 CompletableFuture 来
     * 提供异步处理结果，或返回 null 表示不需要修改 Agent 行为。
     *
     * @param event   要处理的事件
     * @param context 扩展上下文，提供会话和 Agent 状态信息
     * @return 一个 CompletableFuture，完成时包含处理结果（可为 null）
     */
    CompletableFuture<Object> handle(T event, ExtensionContext context);
}
