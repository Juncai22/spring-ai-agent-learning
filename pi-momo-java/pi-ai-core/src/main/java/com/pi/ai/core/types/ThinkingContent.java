package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thinking content block. Appears only in assistant messages when the model
 * uses extended thinking / reasoning.
 * 思考内容块。仅在模型使用扩展思考/推理功能时出现在助手消息中。
 *
 * @param type              always {@code "thinking"} / 固定为 {@code "thinking"}
 * @param thinking          the thinking text / 思考过程的文本内容
 * @param thinkingSignature optional opaque signature for multi-turn reasoning continuity / 可选的不透明签名，用于多轮推理的连续性
 * @param redacted          when {@code true}, the thinking content was redacted by safety filters;
 *                          the encrypted payload is stored in {@code thinkingSignature}
 *                          当为 {@code true} 时，思考内容被安全过滤器遮蔽，
 *                          加密后的载荷存储在 {@code thinkingSignature} 中
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 思考内容块：仅实现 AssistantContentBlock 接口
// 原因：思考过程是 LLM 内部推理，仅出现在助手消息中，不会出现在用户消息中
public record ThinkingContent(
    // 类型标识，固定为 "thinking"
    @JsonProperty("type") String type,
    // 思考过程的文本内容，展示模型如何逐步推理
    @JsonProperty("thinking") String thinking,
    // 思考签名（可选）：不透明字符串，用于多轮推理的连续性
    // 某些 Provider（如 Anthropic）使用此签名在后续轮次中保持推理上下文
    @JsonProperty("thinkingSignature") String thinkingSignature,
    // 是否被截断遮蔽：当安全过滤器检测到敏感内容时，将思考内容替换为加密签名
    @JsonProperty("redacted") Boolean redacted
) implements AssistantContentBlock {

    /**
     * Convenience constructor with only thinking text.
     * 便捷构造方法，仅包含思考文本。
     *
     * @param thinking 思考过程的文本内容
     */
    // Step 1: 便捷构造方法，仅需传入思考文本
    // 原因：大多数场景下不需要签名和遮蔽标记，提供简化的构造器
    public ThinkingContent(String thinking) {
        // Step 2: 委托给主构造器，type 固定为 "thinking"，thinkingSignature 和 redacted 为 null
        this("thinking", thinking, null, null);
    }
}