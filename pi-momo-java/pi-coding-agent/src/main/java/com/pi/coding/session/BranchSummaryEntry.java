package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录分支摘要的会话条目。
 *
 * <p>当离开会话树中的某个分支时，会生成摘要以保留上下文。
 * 这样可以在稍后返回该分支时，仍能了解之前讨论的内容。
 * 分支摘要在构建 LLM 上下文时会被转换为用户消息。
 *
 * <p>验证需求：1.7
 *
 * @param <T>      扩展特定详细信息的类型
 * @param type     固定为 "branch_summary"
 * @param id       唯一条目标识符
 * @param parentId 父条目 ID（第一个条目为 null）
 * @param timestamp ISO 8601 时间戳
 * @param fromId   分支起始条目的 ID
 * @param summary  生成的分支摘要
 * @param details  扩展特定数据（可为 null）
 * @param fromHook 如果由扩展生成则为 true，如果由 pi 生成则为 false/null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BranchSummaryEntry<T>(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("fromId") String fromId,
        @JsonProperty("summary") String summary,
        @JsonProperty("details") T details,
        @JsonProperty("fromHook") Boolean fromHook
) implements SessionEntry {

    /**
     * 创建新的分支摘要条目，包含扩展详细信息。
     *
     * @param id        唯一条目标识符
     * @param parentId  父条目 ID
     * @param timestamp ISO 8601 时间戳
     * @param fromId    分支起始条目的 ID
     * @param summary   分支摘要
     * @param details   扩展特定详细信息（可为 null）
     * @param fromHook  是否由扩展生成
     * @param <T>       详细信息类型
     * @return 新的 BranchSummaryEntry
     */
    public static <T> BranchSummaryEntry<T> create(
            String id,
            String parentId,
            String timestamp,
            String fromId,
            String summary,
            T details,
            Boolean fromHook
    ) {
        return new BranchSummaryEntry<>("branch_summary", id, parentId, timestamp, fromId, summary, details, fromHook);
    }

    /**
     * 创建新的分支摘要条目，不包含扩展详细信息。
     *
     * @param id        唯一条目标识符
     * @param parentId  父条目 ID
     * @param timestamp ISO 8601 时间戳
     * @param fromId    分支起始条目的 ID
     * @param summary   分支摘要
     * @return 新的 BranchSummaryEntry
     */
    public static BranchSummaryEntry<Void> create(
            String id,
            String parentId,
            String timestamp,
            String fromId,
            String summary
    ) {
        return create(id, parentId, timestamp, fromId, summary, null, null);
    }
}