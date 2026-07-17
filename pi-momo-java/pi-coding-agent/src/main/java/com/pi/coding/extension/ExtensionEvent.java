package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 扩展事件密封接口 —— 所有扩展事件类型的基接口。
 *
 * <p>扩展事件在 Agent 生命周期过程中发射，扩展可以订阅这些事件来响应和修改 Agent 行为。
 * 事件类型使用 sealed class 机制限定，确保类型安全。
 *
 * <p>事件分为以下几大类：
 * <ul>
 *   <li><b>资源事件</b>：资源发现（如技能、提示词、主题路径的发现）</li>
 *   <li><b>会话事件</b>：会话生命周期事件（开始、切换、分叉、压缩、树导航、关闭）</li>
 *   <li><b>Agent 事件</b>：Agent 循环生命周期事件（开始、结束、轮次、消息、工具执行）</li>
 *   <li><b>拦截事件</b>：可修改 Agent 行为的拦截点（上下文、提供者请求、输入、工具调用/结果）</li>
 *   <li><b>其他事件</b>：模型选择、用户 bash 命令等</li>
 * </ul>
 *
 * <p>Jackson 多态序列化配置：使用 {@code @JsonTypeInfo} 根据 "type" 字段进行多态分发，
 * 配合 {@code @JsonSubTypes} 注解注册所有事件子类型。
 *
 * <p><b>验证要求：Requirements 6.1-6.24</b>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    // Resource events
    @JsonSubTypes.Type(value = ExtensionEvent.ResourcesDiscoverEvent.class, name = "resources_discover"),
    // Session events
    @JsonSubTypes.Type(value = ExtensionEvent.SessionDirectoryEvent.class, name = "session_directory"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionStartEvent.class, name = "session_start"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionBeforeSwitchEvent.class, name = "session_before_switch"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionSwitchEvent.class, name = "session_switch"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionBeforeForkEvent.class, name = "session_before_fork"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionForkEvent.class, name = "session_fork"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionBeforeCompactEvent.class, name = "session_before_compact"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionCompactEvent.class, name = "session_compact"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionBeforeTreeEvent.class, name = "session_before_tree"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionTreeEvent.class, name = "session_tree"),
    @JsonSubTypes.Type(value = ExtensionEvent.SessionShutdownEvent.class, name = "session_shutdown"),
    // Agent events
    @JsonSubTypes.Type(value = ExtensionEvent.BeforeAgentStartEvent.class, name = "before_agent_start"),
    @JsonSubTypes.Type(value = ExtensionEvent.AgentStartEvent.class, name = "agent_start"),
    @JsonSubTypes.Type(value = ExtensionEvent.AgentEndEvent.class, name = "agent_end"),
    @JsonSubTypes.Type(value = ExtensionEvent.TurnStartEvent.class, name = "turn_start"),
    @JsonSubTypes.Type(value = ExtensionEvent.TurnEndEvent.class, name = "turn_end"),
    @JsonSubTypes.Type(value = ExtensionEvent.MessageStartEvent.class, name = "message_start"),
    @JsonSubTypes.Type(value = ExtensionEvent.MessageUpdateEvent.class, name = "message_update"),
    @JsonSubTypes.Type(value = ExtensionEvent.MessageEndEvent.class, name = "message_end"),
    @JsonSubTypes.Type(value = ExtensionEvent.ToolExecutionStartEvent.class, name = "tool_execution_start"),
    @JsonSubTypes.Type(value = ExtensionEvent.ToolExecutionUpdateEvent.class, name = "tool_execution_update"),
    @JsonSubTypes.Type(value = ExtensionEvent.ToolExecutionEndEvent.class, name = "tool_execution_end"),
    // Interception events
    @JsonSubTypes.Type(value = ExtensionEvent.ContextEvent.class, name = "context"),
    @JsonSubTypes.Type(value = ExtensionEvent.BeforeProviderRequestEvent.class, name = "before_provider_request"),
    @JsonSubTypes.Type(value = ExtensionEvent.InputEvent.class, name = "input"),
    @JsonSubTypes.Type(value = ExtensionEvent.ToolCallEvent.class, name = "tool_call"),
    @JsonSubTypes.Type(value = ExtensionEvent.ToolResultEvent.class, name = "tool_result"),
    // Other events
    @JsonSubTypes.Type(value = ExtensionEvent.ModelSelectEvent.class, name = "model_select"),
    @JsonSubTypes.Type(value = ExtensionEvent.UserBashEvent.class, name = "user_bash")
})
public sealed interface ExtensionEvent permits
        // Resource events
        ExtensionEvent.ResourcesDiscoverEvent,
        // Session events
        ExtensionEvent.SessionDirectoryEvent,
        ExtensionEvent.SessionStartEvent,
        ExtensionEvent.SessionBeforeSwitchEvent,
        ExtensionEvent.SessionSwitchEvent,
        ExtensionEvent.SessionBeforeForkEvent,
        ExtensionEvent.SessionForkEvent,
        ExtensionEvent.SessionBeforeCompactEvent,
        ExtensionEvent.SessionCompactEvent,
        ExtensionEvent.SessionBeforeTreeEvent,
        ExtensionEvent.SessionTreeEvent,
        ExtensionEvent.SessionShutdownEvent,
        // Agent events
        ExtensionEvent.BeforeAgentStartEvent,
        ExtensionEvent.AgentStartEvent,
        ExtensionEvent.AgentEndEvent,
        ExtensionEvent.TurnStartEvent,
        ExtensionEvent.TurnEndEvent,
        ExtensionEvent.MessageStartEvent,
        ExtensionEvent.MessageUpdateEvent,
        ExtensionEvent.MessageEndEvent,
        ExtensionEvent.ToolExecutionStartEvent,
        ExtensionEvent.ToolExecutionUpdateEvent,
        ExtensionEvent.ToolExecutionEndEvent,
        // Interception events
        ExtensionEvent.ContextEvent,
        ExtensionEvent.BeforeProviderRequestEvent,
        ExtensionEvent.InputEvent,
        ExtensionEvent.ToolCallEvent,
        ExtensionEvent.ToolResultEvent,
        // Other events
        ExtensionEvent.ModelSelectEvent,
        ExtensionEvent.UserBashEvent {

    /**
     * 获取事件类型判别字符串。
     *
     * <p>每个事件子类型都返回一个唯一的类型名称（如 "session_start"、"tool_call"），
     * 用于事件分发、序列化和反序列化。
     *
     * @return 事件类型名称字符串
     */
    String type();


    // ══════════════════════════════════════════════════════════════════════════
    // 资源事件
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 在会话启动后触发，允许扩展提供额外的资源路径。
     *
     * <p>扩展可以通过处理此事件返回额外的技能路径、提示词模板路径和主题路径，
     * 这些路径会被加载到系统中。
     *
     * <p><b>验证要求：Requirement 6.1</b>
     *
     * @param cwd    当前工作目录
     * @param reason 发现原因（"startup" 表示启动时，"reload" 表示重载时）
     */
    record ResourcesDiscoverEvent(
        String cwd,
        String reason
    ) implements ExtensionEvent {
        @Override
        public String type() { return "resources_discover"; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 会话事件
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 在会话管理器创建前触发，允许扩展自定义会话目录解析。
     *
     * <p>扩展可以通过返回自定义的会话目录路径来覆盖默认的会话存储位置。
     *
     * <p><b>验证要求：Requirement 6.2</b>
     *
     * @param cwd 当前工作目录
     */
    record SessionDirectoryEvent(
        String cwd
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_directory"; }
    }

    /**
     * 在初始会话加载时触发。
     *
     * <p>当 Agent 首次加载会话文件时触发此事件，扩展可以在此事件中进行
     * 会话相关的初始化操作。
     *
     * <p><b>验证要求：Requirement 6.3</b>
     */
    record SessionStartEvent() implements ExtensionEvent {
        @Override
        public String type() { return "session_start"; }
    }

    /**
     * 在切换会话前触发，可被取消。
     *
     * <p>扩展可以通过返回 {@link EventResult.SessionBeforeSwitchResult} 设置
     * cancel 为 true 来取消会话切换操作。
     *
     * <p><b>验证要求：Requirement 6.4</b>
     *
     * @param reason            切换原因（"new" 表示新建，"resume" 表示恢复）
     * @param targetSessionFile 目标会话文件路径（新建会话时可能为 null）
     */
    record SessionBeforeSwitchEvent(
        String reason,
        String targetSessionFile
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_before_switch"; }
    }

    /**
     * 在切换到另一个会话后触发。
     *
     * <p>扩展可以在此事件中执行会话切换后的清理或初始化工作。
     *
     * <p><b>验证要求：Requirement 6.5</b>
     *
     * @param reason              切换原因（"new" 或 "resume"）
     * @param previousSessionFile 上一个会话的文件路径（可能为 null）
     */
    record SessionSwitchEvent(
        String reason,
        String previousSessionFile
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_switch"; }
    }

    /**
     * 在分叉会话前触发，可被取消。
     *
     * <p>分叉操作会从当前会话树的指定条目创建一个新的分支会话文件。
     *
     * <p><b>验证要求：Requirement 6.6</b>
     *
     * @param entryId 要分叉的条目标识符
     */
    record SessionBeforeForkEvent(
        String entryId
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_before_fork"; }
    }

    /**
     * 在分叉会话后触发。
     *
     * <p><b>验证要求：Requirement 6.7</b>
     *
     * @param previousSessionFile 上一个会话的文件路径（可能为 null）
     */
    record SessionForkEvent(
        String previousSessionFile
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_fork"; }
    }


    /**
     * 在上下文压缩前触发，可被取消或自定义。
     *
     * <p>上下文压缩会汇总会话历史，减少 Token 使用量。
     * 扩展可以提供自定义的压缩结果或取消压缩操作。
     *
     * <p><b>验证要求：Requirement 6.8</b>
     *
     * @param preparation        压缩准备数据
     * @param branchEntries      正在被压缩的分支中的条目列表
     * @param customInstructions 自定义摘要指令（可为 null）
     */
    record SessionBeforeCompactEvent(
        Object preparation,
        java.util.List<com.pi.coding.session.SessionEntry> branchEntries,
        String customInstructions
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_before_compact"; }
    }

    /**
     * 在上下文压缩完成后触发。
     *
     * <p><b>验证要求：Requirement 6.9</b>
     *
     * @param compactionEntry 创建的压缩条目
     * @param fromExtension   压缩是否由扩展触发
     */
    record SessionCompactEvent(
        com.pi.coding.session.CompactionEntry compactionEntry,
        boolean fromExtension
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_compact"; }
    }

    /**
     * 在会话树导航前触发，可被取消。
     *
     * <p>导航操作会将会话切换到目标条目位置，可能触发分支摘要生成。
     *
     * <p><b>验证要求：Requirement 6.10</b>
     *
     * @param preparation 树导航准备数据，包含导航信息和摘要选项
     */
    record SessionBeforeTreeEvent(
        TreePreparation preparation
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_before_tree"; }
    }

    /**
     * 在会话树导航后触发。
     *
     * <p><b>验证要求：Requirement 6.11</b>
     *
     * @param newLeafId     新的叶子条目标识符（可为 null）
     * @param oldLeafId     旧的叶子条目标识符（可为 null）
     * @param summaryEntry  创建的分支摘要条目（可为 null）
     * @param fromExtension 导航是否由扩展触发
     */
    record SessionTreeEvent(
        String newLeafId,
        String oldLeafId,
        com.pi.coding.session.BranchSummaryEntry summaryEntry,
        Boolean fromExtension
    ) implements ExtensionEvent {
        @Override
        public String type() { return "session_tree"; }
    }

    /**
     * 在进程退出时触发。
     *
     * <p>扩展可以在此事件中执行最终的清理操作。
     *
     * <p><b>验证要求：Requirement 6.12</b>
     */
    record SessionShutdownEvent() implements ExtensionEvent {
        @Override
        public String type() { return "session_shutdown"; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Agent 事件
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 在用户提交提示词后、Agent 循环开始前触发。
     *
     * <p>扩展可以在此事件中检查用户的输入，并在 Agent 开始处理之前
     * 注入自定义消息或修改系统提示词。
     *
     * <p><b>验证要求：Requirement 6.15</b>
     *
     * @param prompt       用户的提示词文本
     * @param images       附加的图片（可为 null）
     * @param systemPrompt 当前的系统提示词
     */
    record BeforeAgentStartEvent(
        String prompt,
        java.util.List<com.pi.ai.core.types.ImageContent> images,
        String systemPrompt
    ) implements ExtensionEvent {
        @Override
        public String type() { return "before_agent_start"; }
    }

    /**
     * 在 Agent 循环开始时触发。
     *
     * <p>Agent 循环是 LLM 多轮交互的核心控制循环，每次用户输入都会触发一次循环。
     *
     * <p><b>验证要求：Requirement 6.16</b>
     */
    record AgentStartEvent() implements ExtensionEvent {
        @Override
        public String type() { return "agent_start"; }
    }

    /**
     * 在 Agent 循环结束时触发。
     *
     * <p><b>验证要求：Requirement 6.16</b>
     *
     * @param messages 本次运行中产生的所有新消息
     */
    record AgentEndEvent(
        java.util.List<com.pi.agent.types.AgentMessage> messages
    ) implements ExtensionEvent {
        @Override
        public String type() { return "agent_end"; }
    }


    /**
     * 在每个轮次开始时触发。
     *
     * <p>一个轮次（Turn）代表 Agent 循环中的一次完整 LLM 调用和响应周期。
     * 多轮次交互发生在 Agent 连续调用工具时。
     *
     * <p><b>验证要求：Requirement 6.17</b>
     *
     * @param turnIndex 轮次索引，从 0 开始
     * @param timestamp Unix 时间戳（毫秒）
     */
    record TurnStartEvent(
        int turnIndex,
        long timestamp
    ) implements ExtensionEvent {
        @Override
        public String type() { return "turn_start"; }
    }

    /**
     * 在每个轮次结束时触发。
     *
     * <p><b>验证要求：Requirement 6.17</b>
     *
     * @param turnIndex   轮次索引，从 0 开始
     * @param message     本轮次的助手消息
     * @param toolResults 本轮次产生的工具结果消息列表
     */
    record TurnEndEvent(
        int turnIndex,
        com.pi.agent.types.AgentMessage message,
        java.util.List<com.pi.ai.core.types.ToolResultMessage> toolResults
    ) implements ExtensionEvent {
        @Override
        public String type() { return "turn_end"; }
    }

    /**
     * 在消息开始创建时触发（用户消息、助手消息或工具结果消息）。
     *
     * <p>当任何类型的消息开始被添加到会话中时触发。
     *
     * <p><b>验证要求：Requirement 6.18</b>
     *
     * @param message 正在添加的消息
     */
    record MessageStartEvent(
        com.pi.agent.types.AgentMessage message
    ) implements ExtensionEvent {
        @Override
        public String type() { return "message_start"; }
    }

    /**
     * 在助手消息流式输出期间触发，提供逐 token 更新。
     *
     * <p>当助手正在流式生成回复时，每个内容块更新都会触发此事件。
     * 适用于需要实时监控或处理助手输出的场景。
     *
     * <p><b>验证要求：Requirement 6.18</b>
     *
     * @param message               当前的部分助手消息
     * @param assistantMessageEvent 底层的 LLM 流式事件
     */
    record MessageUpdateEvent(
        com.pi.agent.types.AgentMessage message,
        com.pi.ai.core.event.AssistantMessageEvent assistantMessageEvent
    ) implements ExtensionEvent {
        @Override
        public String type() { return "message_update"; }
    }

    /**
     * 在消息结束时触发。
     *
     * <p><b>验证要求：Requirement 6.18</b>
     *
     * @param message 已完成的消息
     */
    record MessageEndEvent(
        com.pi.agent.types.AgentMessage message
    ) implements ExtensionEvent {
        @Override
        public String type() { return "message_end"; }
    }

    /**
     * 在工具开始执行时触发。
     *
     * <p>当 LLM 决定调用某个工具且工具开始执行时触发。
     *
     * <p><b>验证要求：Requirement 6.19</b>
     *
     * @param toolCallId 工具调用的唯一标识符
     * @param toolName   正在执行的工具名称
     * @param args       已验证的工具参数
     */
    record ToolExecutionStartEvent(
        String toolCallId,
        String toolName,
        Object args
    ) implements ExtensionEvent {
        @Override
        public String type() { return "tool_execution_start"; }
    }

    /**
     * 在工具执行期间触发，提供部分/流式输出。
     *
     * <p>当工具通过 onUpdate 回调提供部分结果时触发。
     *
     * <p><b>验证要求：Requirement 6.19</b>
     *
     * @param toolCallId    工具调用的唯一标识符
     * @param toolName      正在执行的工具名称
     * @param args          已验证的工具参数
     * @param partialResult 工具的 onUpdate 回调提供的部分结果
     */
    record ToolExecutionUpdateEvent(
        String toolCallId,
        String toolName,
        Object args,
        Object partialResult
    ) implements ExtensionEvent {
        @Override
        public String type() { return "tool_execution_update"; }
    }

    /**
     * 在工具执行完成时触发。
     *
     * <p><b>验证要求：Requirement 6.19</b>
     *
     * @param toolCallId 工具调用的唯一标识符
     * @param toolName   已执行的工具名称
     * @param result     最终的工具执行结果
     * @param isError    工具执行是否出错
     */
    record ToolExecutionEndEvent(
        String toolCallId,
        String toolName,
        Object result,
        boolean isError
    ) implements ExtensionEvent {
        @Override
        public String type() { return "tool_execution_end"; }
    }


    // ══════════════════════════════════════════════════════════════════════════
    // 拦截事件
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 在每次 LLM 调用前触发。可以修改消息列表。
     *
     * <p>此事件是扩展修改 LLM 上下文的入口点。处理器可以添加、删除或修改
     * 即将发送给 LLM 的消息列表，从而影响 LLM 的推理结果。
     *
     * <p><b>验证要求：Requirement 6.13</b>
     *
     * @param messages 将要发送给 LLM 的消息列表
     */
    record ContextEvent(
        java.util.List<com.pi.agent.types.AgentMessage> messages
    ) implements ExtensionEvent {
        @Override
        public String type() { return "context"; }
    }

    /**
     * 在提供者请求发送前触发。可以替换请求载荷。
     *
     * <p>此事件在 HTTP 请求发送到 LLM API 之前触发。处理器可以检查或修改
     * 即将发送的 API 请求载荷，实现请求拦截、日志记录或修改。
     *
     * <p><b>验证要求：Requirement 6.14</b>
     *
     * @param payload 将要发送的请求载荷
     */
    record BeforeProviderRequestEvent(
        Object payload
    ) implements ExtensionEvent {
        @Override
        public String type() { return "before_provider_request"; }
    }

    /**
     * 在用户输入被接收、Agent 处理前触发。
     *
     * <p>扩展可以转换输入文本和图片，或者指示输入已被完全处理
     * （无需 Agent 进一步处理）。
     *
     * <p><b>验证要求：Requirement 6.22</b>
     *
     * @param text   输入文本
     * @param images 附加图片（可为 null）
     * @param source 输入来源（"interactive" 表示交互式，"rpc" 表示 RPC 调用，"extension" 表示扩展）
     */
    record InputEvent(
        String text,
        java.util.List<com.pi.ai.core.types.ImageContent> images,
        String source
    ) implements ExtensionEvent {
        @Override
        public String type() { return "input"; }
    }

    /**
     * 在工具执行前触发。可以阻止工具执行。
     *
     * <p>扩展可以检查工具调用的参数，并决定是否阻止该工具的执行。
     * 阻止时可以提供原因说明。
     *
     * <p><b>验证要求：Requirement 6.23</b>
     *
     * @param toolCallId 工具调用的唯一标识符
     * @param toolName   被调用的工具名称
     * @param input      工具输入参数
     */
    record ToolCallEvent(
        String toolCallId,
        String toolName,
        Object input
    ) implements ExtensionEvent {
        @Override
        public String type() { return "tool_call"; }
    }

    /**
     * 在工具执行完成后触发。可以修改结果。
     *
     * <p>扩展可以修改工具执行的结果内容、详情和错误状态。
     * 适用于需要后处理工具执行结果的场景，如结果过滤、转换或增强。
     *
     * <p><b>验证要求：Requirement 6.24</b>
     *
     * @param toolCallId 工具调用的唯一标识符
     * @param toolName   已执行的工具名称
     * @param input      工具输入参数
     * @param content    结果内容块
     * @param details    工具特定的详情信息（可为 null）
     * @param isError    工具执行是否出错
     */
    record ToolResultEvent(
        String toolCallId,
        String toolName,
        Object input,
        java.util.List<com.pi.ai.core.types.ContentBlock> content,
        Object details,
        boolean isError
    ) implements ExtensionEvent {
        @Override
        public String type() { return "tool_result"; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 其他事件
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 在选择了新模型时触发。
     *
     * <p>当用户或系统切换了当前使用的 LLM 模型时触发该事件。
     *
     * <p><b>验证要求：Requirement 6.20</b>
     *
     * @param model         新选择的模型
     * @param previousModel 先前选择的模型（可为 null）
     * @param source        选择来源（"set" 表示手动设置，"cycle" 表示循环切换，"restore" 表示恢复）
     */
    record ModelSelectEvent(
        com.pi.ai.core.types.Model model,
        com.pi.ai.core.types.Model previousModel,
        String source
    ) implements ExtensionEvent {
        @Override
        public String type() { return "model_select"; }
    }

    /**
     * 在用户通过 ! 或 !! 前缀执行 bash 命令时触发。
     *
     * <p><b>验证要求：Requirement 6.21</b>
     *
     * @param command            要执行的命令
     * @param excludeFromContext 如果使用了 !! 前缀则为 true（命令结果不包含在 LLM 上下文中）
     * @param cwd                当前工作目录
     */
    record UserBashEvent(
        String command,
        boolean excludeFromContext,
        String cwd
    ) implements ExtensionEvent {
        @Override
        public String type() { return "user_bash"; }
    }
}
