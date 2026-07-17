package com.pi.coding.compaction;

/**
 * 切割点查找结果记录，描述上下文压缩时切割点的详细信息。
 *
 * <p>上下文压缩需要确定从何处切割历史消息：切割点（cut point）是第一条被保留的条目，
 * 其之前的所有条目将被压缩为摘要。如果切割点位于轮次中间（不是用户消息），
 * 则需要进行轮次拆分（split turn），将轮次前半部分摘要化。
 *
 * <p><b>验证需求: 3.4, 3.5, 3.6, 3.7</b>
 *
 * @param firstKeptEntryIndex 第一条保留条目的索引
 * @param turnStartIndex      被拆分轮次的起始用户消息索引，非拆分轮次时为 -1
 * @param isSplitTurn         是否属于拆分轮次（切割点不是用户消息，位于轮次中间）
 */
public record CutPointResult(
        int firstKeptEntryIndex,
        int turnStartIndex,
        boolean isSplitTurn
) {

    /**
     * 创建表示未找到有效切割点的结果。
     * 此时整个范围都应保留，firstKeptEntryIndex 设为 startIndex。
     *
     * @param startIndex 起始索引
     * @return 表示无有效切割点的 CutPointResult
     */
    public static CutPointResult noValidCutPoint(int startIndex) {
        return new CutPointResult(startIndex, -1, false);
    }
}