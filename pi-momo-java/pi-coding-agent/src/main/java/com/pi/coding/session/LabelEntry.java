package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 标签条目：用于用户定义的书签/标记。
 *
 * <p>标签允许用户标记对话中的特定位置，便于导航。
 * 传入 null 或空字符串可以清除标签。
 * 标签索引在会话管理器中维护，通过 labelsById 映射快速查找。
 *
 * <p>验证需求：1.10
 *
 * @param type      固定为 "label"
 * @param id        唯一条目标识符
 * @param parentId  父条目 ID（第一个条目为 null）
 * @param timestamp ISO 8601 时间戳
 * @param targetId  被标记的条目 ID
 * @param label     标签文本（null 表示清除标签）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LabelEntry(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("targetId") String targetId,
        @JsonProperty("label") String label
) implements SessionEntry {

    /**
     * 创建新的标签条目。
     *
     * @param id        唯一条目标识符
     * @param parentId  父条目 ID
     * @param timestamp ISO 8601 时间戳
     * @param targetId  要标记的条目 ID
     * @param label     标签文本（null 表示清除）
     * @return 新的 LabelEntry
     */
    public static LabelEntry create(
            String id,
            String parentId,
            String timestamp,
            String targetId,
            String label
    ) {
        return new LabelEntry("label", id, parentId, timestamp, targetId, label);
    }
}