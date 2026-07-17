package com.pi.coding.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;

/**
 * Bash 执行消息记录 —— 表示通过 "!" 命令执行的 bash 命令及其结果。
 *
 * <p>该消息类型用于在 Agent 对话中记录 bash 命令的执行情况，包括命令内容、
 * 标准输出/标准错误输出、退出码、取消状态和截断状态。当输出过长时，
 * 支持自动截断并将完整输出保存到外部文件。</p>
 *
 * <p><b>验证需求：Requirement 23.4</b></p>
 *
 * @param command            被执行的 bash 命令原文
 * @param output             命令执行输出（stdout + stderr 合并），可能被截断
 * @param exitCode           进程退出码；如果进程被终止则为 null
 * @param cancelled          命令是否被用户取消
 * @param truncated          输出是否因过长而被截断
 * @param fullOutputPath     当输出被截断时，完整输出文件的保存路径
 * @param timestamp          消息创建时间戳（毫秒）
 * @param excludeFromContext 是否从 LLM 上下文中排除（对应 "!!" 前缀的命令）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BashExecutionMessage(
        @JsonProperty("command") String command,
        @JsonProperty("output") String output,
        @JsonProperty("exitCode") Integer exitCode,
        @JsonProperty("cancelled") boolean cancelled,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("fullOutputPath") String fullOutputPath,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("excludeFromContext") Boolean excludeFromContext
) implements AgentMessage {

    @Override
    public String role() {
        return "bashExecution";
    }

    /**
     * 将 bash 执行结果转换为面向 LLM 的文本格式。
     * <p>
     * 格式化规则：
     * <ul>
     *   <li>命令使用反引号包裹</li>
     *   <li>输出内容置于代码块中</li>
     *   <li>无输出时显示"(no output)"</li>
     *   <li>取消或非零退出码时附加说明</li>
     *   <li>截断时附加完整输出文件路径提示</li>
     * </ul>
     *
     * @return 格式化后的文本表示
     */
    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append("Ran `").append(command).append("`\n");

        if (output != null && !output.isEmpty()) {
            text.append("```\n").append(output).append("\n```");
        } else {
            text.append("(no output)");
        }

        if (cancelled) {
            text.append("\n\n(command cancelled)");
        } else if (exitCode != null && exitCode != 0) {
            text.append("\n\nCommand exited with code ").append(exitCode);
        }

        if (truncated && fullOutputPath != null) {
            text.append("\n\n[Output truncated. Full output: ").append(fullOutputPath).append("]");
        }

        return text.toString();
    }

    @Override
    public String toString() {
        return toText();
    }
}
