package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Tool call content block. Represents an LLM request to invoke a tool.
 * Appears only in assistant messages.
 * 工具调用内容块。表示 LLM 请求调用某个工具的指令，仅出现在助手消息中。
 *
 * @param type             always {@code "toolCall"} / 固定为 {@code "toolCall"}
 * @param id               unique identifier for this tool call / 工具调用的唯一标识
 * @param name             the tool name to invoke / 要调用的工具名称
 * @param arguments        the tool call arguments as a key-value map / 工具调用参数，键值对形式
 * @param thoughtSignature optional opaque signature for reusing thought context (Google-specific) / 可选的不透明签名，用于复用思考上下文（Google 特有）
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 工具调用块：实现 AssistantContentBlock 接口，仅出现在助手消息中
// 原因：工具调用是 LLM 的决策，由 LLM 生成并发送给调用方执行
public record ToolCall(
    // 类型标识，固定为 "toolCall"
    @JsonProperty("type") String type,
    // 工具调用的唯一 ID，用于将执行结果与本次调用匹配
    @JsonProperty("id") String id,
    // 要调用的工具名称，与 Tool 定义中的 name 对应
    @JsonProperty("name") String name,
    // 工具调用参数：键值对 Map，键为参数名，值为参数值
    @JsonProperty("arguments") Map<String, Object> arguments,
    // 思考签名（可选，Google 特有）：用于在多轮对话中复用推理上下文
    @JsonProperty("thoughtSignature") String thoughtSignature
) implements AssistantContentBlock {

    /**
     * Convenience constructor without thoughtSignature.
     * 便捷构造方法，不含 thoughtSignature 参数。
     *
     * @param id        工具调用唯一标识
     * @param name      工具名称
     * @param arguments 工具调用参数
     */
    // Step 1: 便捷构造方法，无需思考签名
    // 原因：thoughtSignature 只有 Google 系列模型使用，大多数场景下为 null
    public ToolCall(String id, String name, Map<String, Object> arguments) {
        // Step 2: 委托给主构造器，type 固定为 "toolCall"，thoughtSignature 为 null
        this("toolCall", id, name, arguments, null);
    }
}