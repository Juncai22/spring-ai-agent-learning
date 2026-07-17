package com.pi.ai.core.types;

/**
 * Content block types that can appear in a {@code UserMessage} or {@code ToolResultMessage}.
 * 可在用户消息（UserMessage）或工具结果消息（ToolResultMessage）中出现的内容块类型的密封接口。
 *
 * <p>Permitted subtypes: {@link TextContent}, {@link ImageContent}.
 * 允许的子类型：{@link TextContent}（文本）、{@link ImageContent}（图片）。
 */
// 密封接口：用户消息和工具结果消息的内容块只能是文本或图片
// 原因：用户不能发送思考过程或工具调用，这些是 LLM 侧的行为
// 继承 ContentBlock 接口，type() 方法由子类实现
public sealed interface UserContentBlock extends ContentBlock permits TextContent, ImageContent {
}