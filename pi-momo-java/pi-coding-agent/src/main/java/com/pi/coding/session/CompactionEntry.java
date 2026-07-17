package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录压缩操作的会话条目。
 *
 * <p>当对话过长时，较旧的消息会被汇总为压缩摘要。
 * 在 LLM 上下文中，压缩摘要会替换被压缩的消息，
 * 但原始消息仍保留在会话文件中。
 *
 * <p>验证需求：1.6
 *
 * @param <T>              扩展特定详细信息的类型
 * @param type             固定为 "compaction"
 * @param id               唯一条目标识符
 * @param parentId         父条目 ID（第一个条目为 null）
 * @param timestamp        ISO 8601 时间戳
 * @param summary          被压缩消息的生成摘要
 * @param firstKeptEntryId 压缩后保留的第一个条目 ID
 * @param tokensBefore     压缩前的 Token 数量
 * @param details          扩展特定数据（可为 null）
 * @param fromHook         如果由扩展生成则为 true，如果由 pi 生成则为 false/null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactionEntry<T>(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("summary") String summary,
        @JsonProperty("firstKeptEntryId") String firstKeptEntryId,
        @JsonProperty("tokensBefore") int tokensBefore,
        @JsonProperty("details") T details,
        @JsonProperty("fromHook") Boolean fromHook
) implements SessionEntry {

    /**
     * 创建新的压缩条目，包含扩展详细信息。
     *
     * @param id               唯一条目标识符
     * @param parentId         父条目 ID
     * @param timestamp        ISO 8601 时间戳
     * @param summary          压缩摘要
     * @param firstKeptEntryId 第一个保留条目的 ID
     * @param tokensBefore     压缩前的 Token 数
     * @param details          扩展特定详细信息（可为 null）
     * @param fromHook         是否由扩展生成
     * @param <T>              详细信息类型
     * @return 新的 CompactionEntry
     */
    public static <T> CompactionEntry<T> create(
            String id,
            String parentId,
            String timestamp,
            String summary,
            String firstKeptEntryId,
            int tokensBefore,
            T details,
            Boolean fromHook
    ) {
        return new CompactionEntry<>("compaction", id, parentId, timestamp, summary, firstKeptEntryId, tokensBefore, details, fromHook);
    }

    /**
     * 创建新的压缩条目，不包含扩展详细信息。
     *
     * @param id               唯一条目标识符
     * @param parentId         父条目 ID
     * @param timestamp        ISO 8601 时间戳
     * @param summary          压缩摘要
     * @param firstKeptEntryId 第一个保留条目的 ID
     * @param tokensBefore     压缩前的 Token 数
     * @return 新的 CompactionEntry
     */
    public static CompactionEntry<Void> create(
            String id,
            String parentId,
            String timestamp,
            String summary,
            String firstKeptEntryId,
            int tokensBefore
    ) {
        return create(id, parentId, timestamp, summary, firstKeptEntryId, tokensBefore, null, null);
    }
}