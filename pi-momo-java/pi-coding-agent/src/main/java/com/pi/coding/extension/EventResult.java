package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;
import com.pi.ai.core.types.ContentBlock;
import com.pi.ai.core.types.ImageContent;
import com.pi.coding.compaction.CompactionResult;

import java.util.List;

/**
 * 事件处理器返回结果类型 —— 定义扩展事件处理器可以返回的所有结果类型。
 *
 * <p>这些记录定义了事件处理器的可能返回值，允许扩展修改 Agent 行为或取消操作。
 * 根据事件类型的不同，处理器可以返回不同的结果类型：
 * <ul>
 *   <li>资源事件结果：资源发现、会话目录</li>
 *   <li>会话事件结果：切换/分叉/压缩/树导航的取消或自定义</li>
 *   <li>Agent 事件结果：注入消息、替换系统提示词</li>
 *   <li>拦截事件结果：修改上下文消息、转换输入、阻止工具执行、修改工具结果</li>
 * </ul>
 *
 * <p>此工具类不可实例化，所有结果类型都是其内部定义的公共记录或密封接口。
 */
public final class EventResult {

    private EventResult() {
        // 工具类，防止实例化
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 资源事件结果
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * resources_discover 事件处理器的返回结果。
     *
     * <p>扩展可以通过此结果提供额外的资源路径，这些路径会被系统加载。
     *
     * @param skillPaths  额外的技能路径
     * @param promptPaths 额外的提示词模板路径
     * @param themePaths  额外的主题路径
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourcesDiscoverResult(
        @JsonProperty("skillPaths") List<String> skillPaths,
        @JsonProperty("promptPaths") List<String> promptPaths,
        @JsonProperty("themePaths") List<String> themePaths
    ) { }

    /**
     * session_directory 事件处理器的返回结果。
     *
     * <p>扩展可以通过此结果自定义会话存储目录。
     *
     * @param sessionDir 自定义会话目录路径
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionDirectoryResult(
        @JsonProperty("sessionDir") String sessionDir
    ) { }

    // ══════════════════════════════════════════════════════════════════════════
    // 会话事件结果
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * session_before_switch 事件处理器的返回结果。
     *
     * @param cancel 是否取消切换操作
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionBeforeSwitchResult(
        @JsonProperty("cancel") Boolean cancel
    ) { }

    /**
     * session_before_fork 事件处理器的返回结果。
     *
     * @param cancel                  是否取消分叉操作
     * @param skipConversationRestore 是否跳过会话恢复
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionBeforeForkResult(
        @JsonProperty("cancel") Boolean cancel,
        @JsonProperty("skipConversationRestore") Boolean skipConversationRestore
    ) { }

    /**
     * session_before_compact 事件处理器的返回结果。
     *
     * @param cancel     是否取消压缩操作
     * @param compaction 自定义压缩结果，替代默认压缩
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionBeforeCompactResult(
        @JsonProperty("cancel") Boolean cancel,
        @JsonProperty("compaction") CompactionResult compaction
    ) { }

    /**
     * session_before_tree 事件处理器的返回结果。
     *
     * @param cancel              是否取消导航操作
     * @param summary             自定义摘要
     * @param customInstructions  覆盖自定义摘要指令
     * @param replaceInstructions 覆盖是否使用自定义指令替换默认提示词
     * @param label               覆盖分支摘要条目的标签
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionBeforeTreeResult(
        @JsonProperty("cancel") Boolean cancel,
        @JsonProperty("summary") SummaryOverride summary,
        @JsonProperty("customInstructions") String customInstructions,
        @JsonProperty("replaceInstructions") Boolean replaceInstructions,
        @JsonProperty("label") String label
    ) { }

    /**
     * 树导航的自定义摘要覆盖。
     *
     * @param summary 摘要文本
     * @param details 附加详情（可为 null）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SummaryOverride(
        @JsonProperty("summary") String summary,
        @JsonProperty("details") Object details
    ) { }

    // ══════════════════════════════════════════════════════════════════════════
    // Agent 事件结果
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * before_agent_start 事件处理器的返回结果。
     *
     * @param message      要注入的自定义消息
     * @param systemPrompt 替换本轮次的系统提示词
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BeforeAgentStartResult(
        @JsonProperty("message") CustomMessageData message,
        @JsonProperty("systemPrompt") String systemPrompt
    ) { }

    /**
     * 用于注入的自定义消息数据。
     *
     * @param customType 自定义类型标识符
     * @param content    消息内容
     * @param display    是否在 UI 中显示
     * @param details    附加详情（可为 null）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomMessageData(
        @JsonProperty("customType") String customType,
        @JsonProperty("content") Object content,
        @JsonProperty("display") boolean display,
        @JsonProperty("details") Object details
    ) { }

    // ══════════════════════════════════════════════════════════════════════════
    // 拦截事件结果
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * context 事件处理器的返回结果。
     *
     * @param messages 修改后的消息列表
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextEventResult(
        @JsonProperty("messages") List<AgentMessage> messages
    ) { }

    /**
     * input 事件处理器的返回结果密封接口。
     *
     * <p>可能的处理结果：
     * <ul>
     *   <li>{@link InputEventResult.Continue}：继续使用原始输入</li>
     *   <li>{@link InputEventResult.Transform}：转换输入文本和图片</li>
     *   <li>{@link InputEventResult.Handled}：输入已被完全处理，无需 Agent 处理</li>
     * </ul>
     */
    public sealed interface InputEventResult permits
            InputEventResult.Continue,
            InputEventResult.Transform,
            InputEventResult.Handled {

        /** 继续使用原始输入，不对输入进行任何修改。 */
        record Continue() implements InputEventResult { }

        /**
         * 转换输入内容。
         *
         * @param text   转换后的文本
         * @param images 转换后的图片（可为 null）
         */
        record Transform(String text, List<ImageContent> images) implements InputEventResult { }

        /** 输入已被扩展完全处理，Agent 无需进一步处理。 */
        record Handled() implements InputEventResult { }
    }

    /**
     * tool_call 事件处理器的返回结果。
     *
     * @param block  是否阻止工具执行
     * @param reason 阻止执行的原因（可为 null）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCallEventResult(
        @JsonProperty("block") Boolean block,
        @JsonProperty("reason") String reason
    ) { }

    /**
     * tool_result 事件处理器的返回结果。
     *
     * @param content 修改后的内容块
     * @param details 修改后的详情
     * @param isError 修改后的错误状态
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResultEventResult(
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("details") Object details,
        @JsonProperty("isError") Boolean isError
    ) { }

    /**
     * user_bash 事件处理器的返回结果。
     *
     * @param operations 自定义执行操作
     * @param result     完整替换结果（扩展完全处理了执行）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserBashEventResult(
        @JsonProperty("operations") Object operations,
        @JsonProperty("result") Object result
    ) { }
}
