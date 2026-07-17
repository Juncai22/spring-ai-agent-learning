package com.pi.coding.session;

/**
 * 所有 Agent 会话事件的标记接口。
 *
 * <p>事件类型包括两大类：
 * <ul>
 *   <li>标准 {@link com.pi.agent.event.AgentEvent} 事件：从底层 Agent 转发过来的事件</li>
 *   <li>编码 Agent 特有事件：如自动压缩（AutoCompactionStartEvent/AutoCompactionEndEvent）、
 *       自动重试（AutoRetryStartEvent/AutoRetryEndEvent）和资源变更事件</li>
 * </ul>
 *
 * <p>监听器通过 {@link AgentSession#subscribe(java.util.function.Consumer)} 注册，
 * 接收所有实现了此接口的事件。
 *
 * <p>验证需求：2.4
 */
public interface AgentSessionEvent {
}