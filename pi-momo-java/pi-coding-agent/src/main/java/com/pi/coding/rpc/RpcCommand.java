package com.pi.coding.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pi.ai.core.types.ImageContent;

import java.util.List;

/**
 * RPC 命令：通过标准输入以 JSON 行格式接收的命令。
 *
 * <p>这是一个密封接口（sealed interface），所有命令类型都定义为嵌套 record。
 * 每个子类型重写 {@link #type()} 返回固定的鉴别器字符串。
 * Jackson 多态序列化通过 {@code type} 字段进行路由。
 *
 * <p>命令分为以下几类：
 * <ul>
 *   <li>提示词相关：Prompt, Steer, FollowUp, Abort</li>
 *   <li>会话管理：NewSession, GetState, SwitchSession, Fork</li>
 *   <li>模型管理：SetModel, CycleModel</li>
 *   <li>思考级别：SetThinkingLevel, CycleThinkingLevel</li>
 *   <li>队列模式：SetSteeringMode, SetFollowUpMode</li>
 *   <li>压缩：Compact, SetAutoCompaction</li>
 *   <li>重试：SetAutoRetry, AbortRetry</li>
 *   <li>Bash 执行：Bash, AbortBash</li>
 *   <li>查询：GetSessionStats, ExportHtml, GetMessages, GetCommands</li>
 * </ul>
 *
 * <p>验证需求：20.3-20.14
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RpcCommand.Prompt.class, name = "prompt"),
    @JsonSubTypes.Type(value = RpcCommand.Steer.class, name = "steer"),
    @JsonSubTypes.Type(value = RpcCommand.FollowUp.class, name = "follow_up"),
    @JsonSubTypes.Type(value = RpcCommand.Abort.class, name = "abort"),
    @JsonSubTypes.Type(value = RpcCommand.NewSession.class, name = "new_session"),
    @JsonSubTypes.Type(value = RpcCommand.GetState.class, name = "get_state"),
    @JsonSubTypes.Type(value = RpcCommand.SetModel.class, name = "set_model"),
    @JsonSubTypes.Type(value = RpcCommand.CycleModel.class, name = "cycle_model"),
    @JsonSubTypes.Type(value = RpcCommand.SetThinkingLevel.class, name = "set_thinking_level"),
    @JsonSubTypes.Type(value = RpcCommand.CycleThinkingLevel.class, name = "cycle_thinking_level"),
    @JsonSubTypes.Type(value = RpcCommand.SetSteeringMode.class, name = "set_steering_mode"),
    @JsonSubTypes.Type(value = RpcCommand.SetFollowUpMode.class, name = "set_follow_up_mode"),
    @JsonSubTypes.Type(value = RpcCommand.Compact.class, name = "compact"),
    @JsonSubTypes.Type(value = RpcCommand.Bash.class, name = "bash"),
    @JsonSubTypes.Type(value = RpcCommand.AbortBash.class, name = "abort_bash"),
    @JsonSubTypes.Type(value = RpcCommand.GetSessionStats.class, name = "get_session_stats"),
    @JsonSubTypes.Type(value = RpcCommand.ExportHtml.class, name = "export_html"),
    @JsonSubTypes.Type(value = RpcCommand.SwitchSession.class, name = "switch_session"),
    @JsonSubTypes.Type(value = RpcCommand.Fork.class, name = "fork"),
    @JsonSubTypes.Type(value = RpcCommand.GetMessages.class, name = "get_messages"),
    @JsonSubTypes.Type(value = RpcCommand.GetCommands.class, name = "get_commands"),
    @JsonSubTypes.Type(value = RpcCommand.SetAutoCompaction.class, name = "set_auto_compaction"),
    @JsonSubTypes.Type(value = RpcCommand.SetAutoRetry.class, name = "set_auto_retry"),
    @JsonSubTypes.Type(value = RpcCommand.AbortRetry.class, name = "abort_retry")
})
public sealed interface RpcCommand {
    /**
     * 命令的唯一标识符，用于关联请求与响应。
     */
    String id();

    /**
     * 命令类型鉴别器，例如 "prompt", "abort", "set_model" 等。
     */
    String type();

    // =========================================================================
    // 提示词相关命令
    // =========================================================================

    /**
     * Prompt 命令：向 Agent 发送提示词消息。
     * 支持可选图片和流式行为配置。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Prompt(@JsonProperty("id") String id,
                  @JsonProperty("message") String message,
                  @JsonProperty("images") List<ImageContent> images,
                  @JsonProperty("streamingBehavior") String streamingBehavior) implements RpcCommand {
        @Override public String type() { return "prompt"; }
    }

    /**
     * Steer 命令：发送引导消息（中断模式），立即插入到当前处理之前。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Steer(@JsonProperty("id") String id,
                 @JsonProperty("message") String message,
                 @JsonProperty("images") List<ImageContent> images) implements RpcCommand {
        @Override public String type() { return "steer"; }
    }

    /**
     * FollowUp 命令：发送跟进消息（等待模式），在当前处理完成后执行。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FollowUp(@JsonProperty("id") String id,
                    @JsonProperty("message") String message,
                    @JsonProperty("images") List<ImageContent> images) implements RpcCommand {
        @Override public String type() { return "follow_up"; }
    }

    /**
     * Abort 命令：中止当前正在进行的操作。
     */
    record Abort(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "abort"; }
    }

    // =========================================================================
    // 会话管理命令
    // =========================================================================

    /**
     * NewSession 命令：创建新会话，可指定父会话用于分支。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NewSession(@JsonProperty("id") String id,
                      @JsonProperty("parentSession") String parentSession) implements RpcCommand {
        @Override public String type() { return "new_session"; }
    }

    /**
     * GetState 命令：获取当前会话的完整状态快照。
     */
    record GetState(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "get_state"; }
    }

    // =========================================================================
    // 模型管理命令
    // =========================================================================

    /**
     * SetModel 命令：切换当前使用的 LLM 模型。
     */
    record SetModel(@JsonProperty("id") String id,
                    @JsonProperty("provider") String provider,
                    @JsonProperty("modelId") String modelId) implements RpcCommand {
        @Override public String type() { return "set_model"; }
    }

    /**
     * CycleModel 命令：循环切换到列表中的下一个可用模型。
     */
    record CycleModel(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "cycle_model"; }
    }

    // =========================================================================
    // 思考级别命令
    // =========================================================================

    /**
     * SetThinkingLevel 命令：设置思考级别（off/low/medium/high）。
     */
    record SetThinkingLevel(@JsonProperty("id") String id,
                            @JsonProperty("level") String level) implements RpcCommand {
        @Override public String type() { return "set_thinking_level"; }
    }

    /**
     * CycleThinkingLevel 命令：循环切换思考级别。
     */
    record CycleThinkingLevel(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "cycle_thinking_level"; }
    }

    // =========================================================================
    // 队列模式命令
    // =========================================================================

    /**
     * SetSteeringMode 命令：设置引导模式（中断模式）。
     */
    record SetSteeringMode(@JsonProperty("id") String id,
                           @JsonProperty("mode") String mode) implements RpcCommand {
        @Override public String type() { return "set_steering_mode"; }
    }

    /**
     * SetFollowUpMode 命令：设置跟进模式（等待模式）。
     */
    record SetFollowUpMode(@JsonProperty("id") String id,
                           @JsonProperty("mode") String mode) implements RpcCommand {
        @Override public String type() { return "set_follow_up_mode"; }
    }

    // =========================================================================
    // 压缩命令
    // =========================================================================

    /**
     * Compact 命令：手动触发会话压缩，将旧消息汇总为摘要以节省上下文空间。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Compact(@JsonProperty("id") String id,
                   @JsonProperty("customInstructions") String customInstructions) implements RpcCommand {
        @Override public String type() { return "compact"; }
    }

    /**
     * SetAutoCompaction 命令：启用或禁用自动压缩功能。
     */
    record SetAutoCompaction(@JsonProperty("id") String id,
                             @JsonProperty("enabled") boolean enabled) implements RpcCommand {
        @Override public String type() { return "set_auto_compaction"; }
    }

    // =========================================================================
    // 重试命令
    // =========================================================================

    /**
     * SetAutoRetry 命令：启用或禁用自动重试功能。
     */
    record SetAutoRetry(@JsonProperty("id") String id,
                        @JsonProperty("enabled") boolean enabled) implements RpcCommand {
        @Override public String type() { return "set_auto_retry"; }
    }

    /**
     * AbortRetry 命令：中止当前正在进行的自动重试。
     */
    record AbortRetry(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "abort_retry"; }
    }

    // =========================================================================
    // Bash 命令
    // =========================================================================

    /**
     * Bash 命令：在会话的工作目录中执行 Bash 命令并返回结果。
     */
    record Bash(@JsonProperty("id") String id,
                @JsonProperty("command") String command) implements RpcCommand {
        @Override public String type() { return "bash"; }
    }

    /**
     * AbortBash 命令：强制中止当前正在执行的 Bash 进程。
     */
    record AbortBash(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "abort_bash"; }
    }

    // =========================================================================
    // 查询命令
    // =========================================================================

    /**
     * GetSessionStats 命令：获取会话统计信息（消息数、状态等）。
     */
    record GetSessionStats(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "get_session_stats"; }
    }

    /**
     * ExportHtml 命令：将会话导出为 HTML 格式。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExportHtml(@JsonProperty("id") String id,
                      @JsonProperty("outputPath") String outputPath) implements RpcCommand {
        @Override public String type() { return "export_html"; }
    }

    /**
     * SwitchSession 命令：切换到不同的会话文件（用于恢复历史会话）。
     */
    record SwitchSession(@JsonProperty("id") String id,
                         @JsonProperty("sessionPath") String sessionPath) implements RpcCommand {
        @Override public String type() { return "switch_session"; }
    }

    /**
     * Fork 命令：从会话树中的指定条目创建分支，生成新的叶子节点。
     */
    record Fork(@JsonProperty("id") String id,
                @JsonProperty("entryId") String entryId) implements RpcCommand {
        @Override public String type() { return "fork"; }
    }

    /**
     * GetMessages 命令：获取当前会话中的所有消息列表。
     */
    record GetMessages(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "get_messages"; }
    }

    /**
     * GetCommands 命令：获取可用的技能和提示模板列表。
     */
    record GetCommands(@JsonProperty("id") String id) implements RpcCommand {
        @Override public String type() { return "get_commands"; }
    }
}