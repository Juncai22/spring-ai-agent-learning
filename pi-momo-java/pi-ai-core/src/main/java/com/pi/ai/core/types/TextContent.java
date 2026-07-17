package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Text content block. Can appear in both user and assistant messages.
 * 文本内容块。可出现在用户消息和助手消息中，是最常用的内容类型。
 *
 * @param type          always {@code "text"} / 固定为 {@code "text"}
 * @param text          the text content / 文本内容
 * @param textSignature optional signature metadata (e.g. OpenAI responses message metadata) / 可选的签名元数据（如 OpenAI Responses API 的消息元数据）
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 实现 UserContentBlock 和 AssistantContentBlock 两个接口
// 原因：文本块在用户消息、助手消息、工具结果消息中都可以出现
public record TextContent(
    // 类型标识，固定为 "text"
    @JsonProperty("type") String type,
    // 文本内容主体
    @JsonProperty("text") String text,
    // 文本签名（可选）：OpenAI Responses API 返回的消息元数据，用于验证消息完整性
    @JsonProperty("textSignature") String textSignature
) implements UserContentBlock, AssistantContentBlock {

    /**
     * Convenience constructor without textSignature.
     * 便捷构造方法，无需 textSignature 参数。
     *
     * @param text 文本内容
     */
    // Step 1: 便捷构造方法，仅需传入文本内容
    // 原因：大多数场景不需要 textSignature，提供简化的构造器
    public TextContent(String text) {
        // Step 2: 委托给主构造器，type 固定为 "text"，textSignature 为 null
        this("text", text, null);
    }
}