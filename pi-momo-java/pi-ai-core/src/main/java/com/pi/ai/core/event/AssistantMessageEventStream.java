package com.pi.ai.core.event;

import com.pi.ai.core.types.AssistantMessage;

/**
 * 专用于 LLM 流式响应的 {@link EventStream} 特化子类。
 *
 * <p>该类继承自 {@link EventStream}，将泛型参数固定为：
 * <ul>
 *   <li>事件类型 E = {@link AssistantMessageEvent} — LLM 响应的各种流式事件</li>
 *   <li>结果类型 R = {@link AssistantMessage} — 流终止时最终产出的完整消息</li>
 * </ul>
 *
 * <p><b>终止条件：</b>当 push 的事件为 {@link AssistantMessageEvent.Done} 或
 * {@link AssistantMessageEvent.Error} 时，流自动终止。
 * <ul>
 *   <li>Done 事件：从事件中提取 {@code message} 作为最终结果，表示正常完成</li>
 *   <li>Error 事件：从事件中提取 {@code error} 作为最终结果，表示错误终止</li>
 * </ul>
 *
 * <p><b>典型用法示例：</b>
 * <pre>{@code
 * // 创建流实例
 * var stream = AssistantMessageEventStream.create();
 *
 * // ---- 生产者线程 ----
 * // 推送流开始事件
 * stream.push(new AssistantMessageEvent.Start(partialMsg));
 * // 推送文本内容增量
 * stream.push(new AssistantMessageEvent.TextDelta(0, "你好", partialMsg));
 * stream.push(new AssistantMessageEvent.TextDelta(0, "世界", partialMsg));
 * // 推送文本内容结束
 * stream.push(new AssistantMessageEvent.TextEnd(0, "你好世界", partialMsg));
 * // 推送正常完成事件（触发自动终止）
 * stream.push(new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));
 * // 显式结束流
 * stream.end(null);
 *
 * // ---- 消费者线程 ----
 * // 阻塞迭代所有事件
 * for (AssistantMessageEvent event : stream) {
 *     switch (event) {
 *         case AssistantMessageEvent.TextDelta(var idx, var delta, var _) ->
 *             System.out.print(delta);  // 流式输出
 *         case AssistantMessageEvent.Done(var _, var msg) ->
 *             System.out.println("[完成] " + msg);
 *         default -> {}
 *     }
 * }
 * // 阻塞等待最终结果
 * AssistantMessage result = stream.result().join();
 * }</pre>
 */
public class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {

    /**
     * 创建 AssistantMessageEventStream 实例。
     *
     * <p>调用父类 {@link EventStream} 构造器，传入：
     * <ul>
     *   <li><b>isComplete 谓词：</b>判断事件是否为 {@link AssistantMessageEvent.Done} 或
     *       {@link AssistantMessageEvent.Error}，这两类事件表示流式响应结束</li>
     *   <li><b>extractResult 函数：</b>从终止事件中提取最终 AssistantMessage：
     *       <ul>
     *         <li>Done 事件返回 {@code d.message()} — 正常完成时的完整消息</li>
     *         <li>Error 事件返回 {@code e.error()} — 错误终止时的错误消息</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    public AssistantMessageEventStream() {
        // 调用父类构造器，传入 isComplete 谓词和 extractResult 提取函数
        super(
            // isComplete 谓词：判断事件是否为 Done 或 Error 类型，这两类事件表示流式响应结束
            event -> event instanceof AssistantMessageEvent.Done
                  || event instanceof AssistantMessageEvent.Error,
            // extractResult 函数：从终止事件中提取最终 AssistantMessage
            event -> {
                // 如果是 Done 事件，提取正常完成的完整消息（d.message()）
                if (event instanceof AssistantMessageEvent.Done d) {
                    return d.message();
                }
                // 如果是 Error 事件，提取错误终止的错误消息（e.error()）
                if (event instanceof AssistantMessageEvent.Error e) {
                    return e.error();
                }
                // 如果既不是 Done 也不是 Error，说明 isComplete 判断有误，抛出异常
                throw new IllegalStateException("非预期的终止事件类型: " + event.getClass().getSimpleName());
            }
        );
    }

    /**
     * 工厂方法，创建新的 AssistantMessageEventStream 实例。
     *
     * <p>推荐使用此工厂方法而非直接调用构造器，因为方法名 {@code create()} 语义更清晰，
     * 且便于后续扩展（如引入缓存池或代理模式）。
     *
     * @return 新的 AssistantMessageEventStream 实例，尚未开始接收事件
     */
    public static AssistantMessageEventStream create() {
        // 创建并返回新的 AssistantMessageEventStream 实例
        return new AssistantMessageEventStream();
    }
}