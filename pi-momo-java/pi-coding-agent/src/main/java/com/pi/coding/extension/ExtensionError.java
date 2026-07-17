package com.pi.coding.extension;

/**
 * 扩展事件处理器中发生的错误记录。
 *
 * <p>当扩展的事件处理器抛出异常时，Runner 会创建一个 ExtensionError 记录，
 * 包含错误信息，并通过错误监听器通知外部。
 * 此记录用于错误诊断和日志记录。
 *
 * <p>错误信息包括：
 * <ul>
 *   <li>事件类型：发生错误的事件名称</li>
 *   <li>错误消息：异常的详细信息</li>
 *   <li>根本原因：原始的异常对象（可为 null）</li>
 * </ul>
 *
 * @param event   发生错误的事件类型名称（如 "session_start"、"tool_call"）
 * @param message 错误消息
 * @param cause   根本异常（可为 null）
 */
public record ExtensionError(
    String event,
    String message,
    Throwable cause
) { }
