package com.pi.ai.core.types;

/**
 * Content block types that can appear in an {@code AssistantMessage}.
 * 可在助手消息（AssistantMessage）中出现的内容块类型的密封接口。
 *
 * <p>Permitted subtypes: {@link TextContent}, {@link ThinkingContent}, {@link ToolCall}.
 * 允许的子类型：{@link TextContent}（文本）、{@link ThinkingContent}（思考过程）、{@link ToolCall}（工具调用）。
 */
// 密封接口：助手消息的内容块只能是文本、思考过程或工具调用
// 原因：助手消息不能包含图片（图片属于用户输入或工具结果）
// 继承 ContentBlock 接口，type() 方法由子类实现
public sealed interface AssistantContentBlock extends ContentBlock permits TextContent, ThinkingContent, ToolCall {
}