package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;

import java.util.List;

/**
 * 压缩准备数据记录，封装了执行一次上下文压缩所需的所有前置信息。
 *
 * <p>扩展点：允许外部扩展通过此记录自定义压缩行为（如自定义摘要生成策略）。
 * 会话管理器通过此记录获取压缩所需的所有输入数据，执行压缩后重新加载会话。
 *
 * <p><b>验证需求: 3.8</b>
 *
 * @param firstKeptEntryId     压缩后保留的第一条条目的 ID
 * @param messagesToSummarize  将被摘要化并丢弃的消息列表
 * @param turnPrefixMessages   拆分轮次时，将被转为轮次前缀摘要的消息列表
 * @param isSplitTurn          是否为拆分轮次（切割点在轮次中间，不在用户消息处）
 * @param tokensBefore         压缩前的 token 数量
 * @param previousSummary      上一次压缩的摘要内容，用于增量更新模式
 * @param fileOps              从待摘要消息中提取的文件操作信息
 * @param settings             压缩设置
 */
public record CompactionPreparation(
        String firstKeptEntryId,
        List<AgentMessage> messagesToSummarize,
        List<AgentMessage> turnPrefixMessages,
        boolean isSplitTurn,
        int tokensBefore,
        String previousSummary,
        FileOperations fileOps,
        CompactionSettings settings
) {

    /**
     * 检查是否有需要摘要化的消息。
     *
     * @return 如果有待摘要消息则返回 true
     */
    public boolean hasMessagesToSummarize() {
        return messagesToSummarize != null && !messagesToSummarize.isEmpty();
    }

    /**
     * 检查是否有轮次前缀消息需要摘要化。
     * 在拆分轮次场景下，轮次前半部分需要被摘要为上下文，后半部分保留原始内容。
     *
     * @return 如果有待摘要的轮次前缀消息则返回 true
     */
    public boolean hasTurnPrefixMessages() {
        return turnPrefixMessages != null && !turnPrefixMessages.isEmpty();
    }
}