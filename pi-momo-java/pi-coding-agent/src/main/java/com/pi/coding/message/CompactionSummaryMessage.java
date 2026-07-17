package com.pi.coding.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;

/**
 * 压缩摘要消息记录 —— 表示对话历史被压缩后的摘要信息。
 *
 * <p>当 Agent 对话上下文过长时，系统会对历史消息进行压缩（Compaction），
 * 将早期对话内容提炼为简洁摘要，以节省 LLM 上下文窗口空间。
 * 该消息记录了压缩后的摘要以及压缩前的 Token 数量。</p>
 *
 * <p><b>验证需求：Requirement 23.5</b></p>
 *
 * @param summary      对话历史压缩摘要文本
 * @param tokensBefore 压缩前的 Token 数量，用于监控上下文使用情况
 * @param timestamp    消息时间戳（毫秒）
 */
public record CompactionSummaryMessage(
        @JsonProperty("summary") String summary,
        @JsonProperty("tokensBefore") int tokensBefore,
        @JsonProperty("timestamp") long timestamp
) implements AgentMessage {

    @Override
    public String role() {
        return "compactionSummary";
    }

    @Override
    public String toString() {
        return summary;
    }
}
