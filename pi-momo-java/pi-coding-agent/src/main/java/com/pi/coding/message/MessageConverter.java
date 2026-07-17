package com.pi.coding.message;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.Message;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.UserContentBlock;
import com.pi.ai.core.types.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息转换器 —— 将 Agent 内部消息（AgentMessage，包括自定义类型）转换为
 * LLM（大语言模型）兼容的 Message 对象。
 *
 * <p>这是 Agent 消息处理链中的核心转换组件，负责桥接 Agent 内部消息模型
 * 和 LLM 的消息模型。它处理以下使用场景：</p>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li><b>Agent 的 convertToLlm 选项</b>：在 prompt 调用和队列消息处理时，
 *       将内部消息格式转换为 LLM 可理解的格式</li>
 *   <li><b>Compaction 的 generateSummary</b>：在对话历史压缩时，
 *       将历史消息提炼为摘要</li>
 *   <li><b>扩展和自定义工具</b>：允许第三方扩展通过自定义消息类型
 *       向 LLM 上下文注入信息</li>
 * </ul>
 *
 * <h3>消息类型转换规则</h3>
 * <table>
 *   <tr><th>Agent 消息类型</th><th>LLM 消息类型</th><th>说明</th></tr>
 *   <tr><td>MessageAdapter</td><td>透传</td><td>标准 LLM 消息直接透传</td></tr>
 *   <tr><td>bashExecution</td><td>UserMessage</td><td>格式化为命令+输出+退出码</td></tr>
 *   <tr><td>custom</td><td>UserMessage</td><td>内容可以是字符串或内容块列表</td></tr>
 *   <tr><td>branchSummary</td><td>UserMessage</td><td>包裹分支摘要前缀/后缀</td></tr>
 *   <tr><td>compactionSummary</td><td>UserMessage</td><td>包裹压缩摘要前缀/后缀</td></tr>
 *   <tr><td>未知类型</td><td>过滤丢弃</td><td>不支持的类型的消息被静默过滤</td></tr>
 * </table>
 *
 * <p><b>验证需求：Requirements 23.1-23.8</b></p>
 */
public final class MessageConverter {

    /**
     * 压缩摘要消息的前缀标记。
     * 用于在 LLM 上下文中标识压缩摘要的起始位置，
     * 使 LLM 能够理解这段文本是历史对话的压缩表示。
     */
    public static final String COMPACTION_SUMMARY_PREFIX =
            "The conversation history before this point was compacted into the following summary:\n\n<summary>\n";

    /**
     * 压缩摘要消息的后缀标记。
     * 与 {@link #COMPACTION_SUMMARY_PREFIX} 配合使用，
     * 用 XML 风格的 summary 标签包裹摘要内容。
     */
    public static final String COMPACTION_SUMMARY_SUFFIX = "\n</summary>";

    /**
     * 分支摘要消息的前缀标记。
     * 用于在 LLM 上下文中标识分支摘要的起始位置，
     * 告知 LLM 这段文本是来自分支对话的摘要。
     */
    public static final String BRANCH_SUMMARY_PREFIX =
            "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n";

    /**
     * 分支摘要消息的后缀标记。
     * 与 {@link #BRANCH_SUMMARY_PREFIX} 配合使用，
     * 用 XML 风格的 summary 标签包裹分支摘要内容。
     */
    public static final String BRANCH_SUMMARY_SUFFIX = "</summary>";

    /**
     * 私有构造方法，防止实例化工具类。
     * MessageConverter 只提供静态方法，无需实例化。
     */
    private MessageConverter() {
        // Utility class
    }

    /**
     * 将 Agent 消息列表批量转换为 LLM 兼容的 Message 列表。
     *
     * <p>遍历消息列表中的每条消息，调用 {@link #convertSingle} 进行类型分发转换。
     * 转换结果为 null 的消息（如被排除的 bash 执行或不支持的类型）将被自动过滤，
     * 不会出现在返回列表中。</p>
     *
     * <p>该方法在以下场景中被调用：</p>
     * <ul>
     *   <li>Agent 构建 LLM 请求消息列表时</li>
     *   <li>Compaction 构建历史摘要时</li>
     *   <li>扩展工具注入消息时</li>
     * </ul>
     *
     * @param messages 待转换的 Agent 消息列表，不能为 null
     * @return 转换后的 LLM 兼容消息列表（已过滤 null 元素，不会返回 null）
     */
    public static List<Message> convertToLlm(List<AgentMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (AgentMessage m : messages) {
            Message converted = convertSingle(m);
            if (converted != null) {
                result.add(converted);
            }
        }
        return result;
    }

    /**
     * 转换单条 Agent 消息为 LLM 兼容的 Message 对象。
     *
     * <p>根据消息类型进行分发：</p>
     * <ul>
     *   <li><b>MessageAdapter</b>：直接透传内部的 Message 对象</li>
     *   <li><b>bashExecution</b>：委托给 {@link #convertBashExecution}</li>
     *   <li><b>custom</b>：委托给 {@link #convertCustom}</li>
     *   <li><b>branchSummary</b>：委托给 {@link #convertBranchSummary}</li>
     *   <li><b>compactionSummary</b>：委托给 {@link #convertCompactionSummary}</li>
     *   <li><b>user/assistant/toolResult</b>：非适配器包装的标准消息，兜底处理</li>
     *   <li><b>其他类型</b>：返回 null，消息将被过滤丢弃</li>
     * </ul>
     *
     * @param m 待转换的 Agent 消息，不能为 null
     * @return 转换后的 Message 对象，如果消息应被过滤则返回 null
     */
    static Message convertSingle(AgentMessage m) {
        // 标准 LLM 消息（由 MessageAdapter 包装）—— 直接透传底层 Message 对象
        if (m instanceof MessageAdapter adapter) {
            return adapter.message();
        }

        // 根据消息的 role 字段进行类型分发转换
        String role = m.role();
        return switch (role) {
            case "bashExecution" -> convertBashExecution(m);
            case "custom" -> convertCustom(m);
            case "branchSummary" -> convertBranchSummary(m);
            case "compactionSummary" -> convertCompactionSummary(m);
            case "user", "assistant", "toolResult" -> {
                // 非适配器包装的标准消息 —— 正常情况下不应发生，
                // 此处做兜底处理，通过 toString 创建用户消息
                yield createTextUserMessage(m.toString(), m.timestamp());
            }
            // 未知类型的消息直接被过滤掉，不影响 LLM 上下文
            default -> null;
        };
    }

    /**
     * 将 Bash 执行消息转换为 LLM 用户消息。
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>如果 {@link BashExecutionMessage#excludeFromContext()} 为 true（对应 "!!" 前缀命令），
     *       则返回 null，消息被过滤</li>
     *   <li>否则调用 {@link BashExecutionMessage#toText()} 格式化为文本，包裹为 UserMessage</li>
     * </ul>
     *
     * @param m 待转换的 Agent 消息
     * @return 转换后的 UserMessage，或 null（被排除时）
     */
    private static Message convertBashExecution(AgentMessage m) {
        if (m instanceof BashExecutionMessage bash) {
            // 检查 excludeFromContext 标记：如果为 true，说明该命令以 "!!" 前缀执行，
            // 其输出应被排除在 LLM 上下文之外（例如敏感操作或大量输出）
            if (Boolean.TRUE.equals(bash.excludeFromContext())) {
                return null;
            }
            return createTextUserMessage(bash.toText(), bash.timestamp());
        }
        // 兜底处理：如果消息不是 BashExecutionMessage 类型，使用 toString 转换
        return createTextUserMessage(m.toString(), m.timestamp());
    }

    /**
     * 将自定义消息转换为 LLM 用户消息。
     *
     * <p>根据 content 字段的实际类型进行不同处理：</p>
     * <ul>
     *   <li>String 类型：直接作为文本内容创建 UserMessage</li>
     *   <li>List 类型（ContentBlock 列表）：透传为 UserMessage 的内容块</li>
     *   <li>其他类型：使用 String.valueOf() 转换为字符串</li>
     * </ul>
     *
     * @param m 待转换的 Agent 消息
     * @return 转换后的 UserMessage
     */
    @SuppressWarnings("unchecked")
    private static Message convertCustom(AgentMessage m) {
        if (m instanceof CustomMessage custom) {
            Object content = custom.content();
            if (content instanceof String text) {
                // 纯文本内容，直接创建文本用户消息
                return createTextUserMessage(text, custom.timestamp());
            }
            if (content instanceof List<?> blocks) {
                // 内容块列表（如文本+图片组合），透传为 UserMessage 的内容块
                // 这种方式支持多模态消息的传递
                return new UserMessage("user", blocks, custom.timestamp());
            }
            // 兜底处理：对其他类型使用 String.valueOf()
            return createTextUserMessage(String.valueOf(content), custom.timestamp());
        }
        // 兜底处理：非 CustomMessage 类型的使用 toString
        return createTextUserMessage(m.toString(), m.timestamp());
    }

    /**
     * 将分支摘要消息转换为 LLM 用户消息，并用摘要前缀/后缀包裹。
     *
     * <p>转换后的消息格式为：</p>
     * <pre>
     * The following is a summary of a branch that this conversation came back from:
     *
     * &lt;summary&gt;
     * [分支摘要内容]
     * &lt;/summary&gt;
     * </pre>
     *
     * <p>这种格式使 LLM 能够清晰识别分支摘要的边界和语义，
     * 理解这段文本是来自分支对话的压缩摘要而非当前对话的即时内容。</p>
     *
     * @param m 待转换的 Agent 消息
     * @return 包裹了前缀/后缀的 UserMessage
     */
    private static Message convertBranchSummary(AgentMessage m) {
        String summary;
        if (m instanceof BranchSummaryMessage bsm) {
            summary = bsm.summary();
        } else {
            summary = m.toString();
        }
        return createTextUserMessage(
                BRANCH_SUMMARY_PREFIX + summary + BRANCH_SUMMARY_SUFFIX,
                m.timestamp()
        );
    }

    /**
     * 将压缩摘要消息转换为 LLM 用户消息，并用压缩摘要前缀/后缀包裹。
     *
     * <p>转换后的消息格式为：</p>
     * <pre>
     * The conversation history before this point was compacted into the following summary:
     *
     * &lt;summary&gt;
     * [压缩摘要内容]
     * &lt;/summary&gt;
     * </pre>
     *
     * <p>这种格式使 LLM 能够理解当前对话之前的历史已被压缩为摘要，
     * 避免 LLM 将摘要内容误解为当前对话的即时消息。</p>
     *
     * @param m 待转换的 Agent 消息
     * @return 包裹了前缀/后缀的 UserMessage
     */
    private static Message convertCompactionSummary(AgentMessage m) {
        String summary;
        if (m instanceof CompactionSummaryMessage csm) {
            summary = csm.summary();
        } else {
            summary = m.toString();
        }
        return createTextUserMessage(
                COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX,
                m.timestamp()
        );
    }

    /**
     * 创建包含纯文本内容块的用户消息（UserMessage）。
     *
     * <p>这是一个底层辅助方法，将纯文本字符串和时戳封装为 LLM 可识别的
     * UserMessage 对象，消息角色固定为 "user"。</p>
     *
     * @param text      消息文本内容，不能为 null
     * @param timestamp 消息时间戳（毫秒），用于消息排序和追踪
     * @return 包含单个 TextContent 的 UserMessage 对象
     */
    private static UserMessage createTextUserMessage(String text, long timestamp) {
        return new UserMessage(
                "user",
                List.of(new TextContent(text)),
                timestamp
        );
    }
}
