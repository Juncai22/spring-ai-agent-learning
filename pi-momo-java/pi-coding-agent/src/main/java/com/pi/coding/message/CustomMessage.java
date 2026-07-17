package com.pi.coding.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;

/**
 * 自定义消息记录 —— 表示扩展（Extension）通过 sendMessage() 注入到对话中的消息。
 *
 * <p>扩展机制允许第三方模块向 Agent 对话中注入自定义内容，例如：
 * <ul>
 *   <li>工具执行结果的状态报告</li>
 *   <li>外部系统的事件通知</li>
 *   <li>自定义工作流的中间状态</li>
 * </ul>
 * 内容支持纯文本字符串或内容块（ContentBlock）列表两种格式。</p>
 *
 * <p><b>验证需求：Requirement 23.6</b></p>
 *
 * @param customType 扩展标识符，用于过滤和路由自定义消息
 * @param content    消息内容，可以是 String 或 ContentBlock 列表
 * @param display    是否在 TUI（终端用户界面）中显示
 * @param details    扩展特定的元数据（可为 null），用于传递额外结构化信息
 * @param timestamp  消息时间戳（毫秒）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomMessage(
        @JsonProperty("customType") String customType,
        @JsonProperty("content") Object content,
        @JsonProperty("display") boolean display,
        @JsonProperty("details") Object details,
        @JsonProperty("timestamp") long timestamp
) implements AgentMessage {

    @Override
    public String role() {
        return "custom";
    }

    @Override
    public String toString() {
        return content instanceof String ? (String) content : String.valueOf(content);
    }
}
