package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录活跃模型变更的会话条目。
 *
 * <p>用户切换到不同的 LLM 提供者或模型时记录此条目。
 * 包含提供者和模型标识，用于在构建会话上下文时提取当前模型信息。
 *
 * <p>验证需求：1.5
 *
 * @param type      固定为 "model_change"
 * @param id        唯一条目标识符
 * @param parentId  父条目 ID（第一个条目为 null）
 * @param timestamp ISO 8601 时间戳
 * @param provider  提供商标识（如 "anthropic"、"openai"）
 * @param modelId   模型标识（如 "claude-3-opus"）
 */
public record ModelChangeEntry(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("provider") String provider,
        @JsonProperty("modelId") String modelId
) implements SessionEntry {

    /**
     * 创建新的模型变更条目。
     *
     * @param id        唯一条目标识符
     * @param parentId  父条目 ID
     * @param timestamp ISO 8601 时间戳
     * @param provider  提供商标识
     * @param modelId   模型标识
     * @return 新的 ModelChangeEntry
     */
    public static ModelChangeEntry create(String id, String parentId, String timestamp, String provider, String modelId) {
        return new ModelChangeEntry("model_change", id, parentId, timestamp, provider, modelId);
    }
}