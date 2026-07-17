package com.pi.coding.compaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 上下文压缩操作的结果记录。
 *
 * <p>封装了压缩操作的完整输出，包括生成的摘要内容、保留的起始条目 ID、
 * 压缩前的 token 数量以及扩展特定的详情数据（如文件操作列表）。
 *
 * <p><b>验证需求: 3.8</b>
 *
 * @param <T>              扩展特定详情的类型
 * @param summary          压缩生成的摘要内容
 * @param firstKeptEntryId 压缩后保留的第一条条目的 ID
 * @param tokensBefore     压缩前的 token 数量
 * @param details          扩展特定数据（如文件列表 CompactionDetails）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactionResult<T>(
        @JsonProperty("summary") String summary,
        @JsonProperty("firstKeptEntryId") String firstKeptEntryId,
        @JsonProperty("tokensBefore") int tokensBefore,
        @JsonProperty("details") T details
) {

    /**
     * 创建不包含扩展详情的结果。
     *
     * @param summary          摘要内容
     * @param firstKeptEntryId 保留的第一条条目 ID
     * @param tokensBefore     压缩前 token 数量
     * @return CompactionResult 实例，details 为 null
     */
    public static CompactionResult<Void> of(String summary, String firstKeptEntryId, int tokensBefore) {
        return new CompactionResult<>(summary, firstKeptEntryId, tokensBefore, null);
    }

    /**
     * 创建包含文件操作详情的结果。
     *
     * @param summary       摘要内容
     * @param firstKeptEntryId 保留的第一条条目 ID
     * @param tokensBefore  压缩前 token 数量
     * @param readFiles     仅读取的文件列表
     * @param modifiedFiles 被修改的文件列表
     * @return 包含 CompactionDetails 的 CompactionResult 实例
     */
    public static CompactionResult<CompactionDetails> withFileOps(
            String summary,
            String firstKeptEntryId,
            int tokensBefore,
            List<String> readFiles,
            List<String> modifiedFiles
    ) {
        return new CompactionResult<>(
                summary,
                firstKeptEntryId,
                tokensBefore,
                new CompactionDetails(readFiles, modifiedFiles)
        );
    }
}