package com.pi.coding.session;

/**
 * 自动压缩开始事件。
 *
 * <p>当自动压缩即将开始时触发此事件。
 * 监听器可以在此事件中执行压缩前的准备工作，
 * 或更新 UI 显示压缩进度。
 *
 * @param reason 压缩触发原因：
 *               <ul>
 *                 <li>"overflow" - 上下文溢出</li>
 *                 <li>"threshold" - 达到阈值触发</li>
 *               </ul>
 */
public record AutoCompactionStartEvent(String reason) implements AgentSessionEvent {
}