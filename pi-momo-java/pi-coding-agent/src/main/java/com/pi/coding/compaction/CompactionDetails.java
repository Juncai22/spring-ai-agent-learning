package com.pi.coding.compaction;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 压缩详情记录，存储在 CompactionEntry.details 中，用于文件追踪。
 *
 * <p>记录一次上下文压缩操作所涉及的文件信息，包括只读文件和被修改的文件。
 * 这些信息会在压缩摘要中追加，以便后续轮次了解哪些文件被操作过。
 *
 * <p><b>验证需求: 3.12</b>
 *
 * @param readFiles     仅被读取（未被修改）的文件路径列表
 * @param modifiedFiles 被写入或编辑（修改过）的文件路径列表
 */
public record CompactionDetails(
        @JsonProperty("readFiles") List<String> readFiles,
        @JsonProperty("modifiedFiles") List<String> modifiedFiles
) {

    /**
     * 创建空的压缩详情，表示没有文件操作记录。
     *
     * @return 空的 CompactionDetails 实例
     */
    public static CompactionDetails empty() {
        return new CompactionDetails(List.of(), List.of());
    }
}