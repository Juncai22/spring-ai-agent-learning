package com.pi.coding.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;

/**
 * 分支摘要消息记录 —— 表示 Agent 从子分支回到主分支时的分支摘要信息。
 *
 * <p>当 Agent 在对话中创建分支（例如通过 git worktree 进行独立探索），
 * 回到主分支时需要将分支中的关键信息压缩为摘要，以便 LLM 理解分支期间的上下文。</p>
 *
 * <p><b>验证需求：Requirement 23.5</b></p>
 *
 * @param summary   分支摘要文本，包含分支期间的关键发现和决策
 * @param fromId    分支起始点的消息/条目 ID，用于追溯分支来源
 * @param timestamp 消息时间戳（毫秒）
 */
public record BranchSummaryMessage(
        @JsonProperty("summary") String summary,
        @JsonProperty("fromId") String fromId,
        @JsonProperty("timestamp") long timestamp
) implements AgentMessage {

    @Override
    public String role() {
        return "branchSummary";
    }

    @Override
    public String toString() {
        return summary;
    }
}
