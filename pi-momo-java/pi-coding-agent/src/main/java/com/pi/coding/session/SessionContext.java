package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;

import java.util.List;

/**
 * 会话上下文：包含解析后的状态，用于 LLM 交互。
 *
 * <p>通过从当前叶子节点到根节点的会话树遍历构建，
 * 收集消息，提取当前的思考级别和模型信息。
 * 处理路径上的压缩摘要和分支摘要。
 *
 * <p>验证需求：1.12
 *
 * @param messages      要发送给 LLM 的 Agent 消息列表
 * @param thinkingLevel 当前思考级别（如 "off"、"low"、"medium"、"high"）
 * @param model         当前模型信息（未设置时可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionContext(
        @JsonProperty("messages") List<AgentMessage> messages,
        @JsonProperty("thinkingLevel") String thinkingLevel,
        @JsonProperty("model") ModelInfo model
) {
    /**
     * 模型信息，包含提供商标识和模型 ID。
     *
     * @param provider 提供商标识（如 "anthropic"、"openai"）
     * @param modelId  模型标识（如 "claude-3-opus"）
     */
    public record ModelInfo(
            @JsonProperty("provider") String provider,
            @JsonProperty("modelId") String modelId
    ) {
        /**
         * 创建 ModelInfo 实例。
         *
         * @param provider 提供商标识
         * @param modelId  模型标识
         * @return 新的 ModelInfo 实例
         */
        public static ModelInfo of(String provider, String modelId) {
            return new ModelInfo(provider, modelId);
        }
    }

    /**
     * 创建空的会话上下文，使用默认值。
     *
     * @return 包含空消息列表、"off" 思考级别和 null 模型的 SessionContext
     */
    public static SessionContext empty() {
        return new SessionContext(List.of(), "off", null);
    }

    /**
     * 创建包含给定消息的会话上下文，使用默认设置。
     *
     * @param messages Agent 消息列表
     * @return 包含 "off" 思考级别和 null 模型的 SessionContext
     */
    public static SessionContext of(List<AgentMessage> messages) {
        return new SessionContext(messages, "off", null);
    }

    /**
     * 创建包含所有字段的会话上下文。
     *
     * @param messages      消息列表
     * @param thinkingLevel 思考级别
     * @param provider      模型提供者（可为 null）
     * @param modelId       模型 ID（可为 null）
     * @return 新的 SessionContext
     */
    public static SessionContext of(List<AgentMessage> messages, String thinkingLevel, String provider, String modelId) {
        ModelInfo model = (provider != null && modelId != null) ? ModelInfo.of(provider, modelId) : null;
        return new SessionContext(messages, thinkingLevel, model);
    }
}