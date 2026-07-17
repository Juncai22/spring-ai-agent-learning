package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;

import java.util.List;

/**
 * 上下文压缩工具类，提供 token 计算、估算和压缩决策逻辑。
 *
 * <p>核心功能包括：
 * <ul>
 *   <li>从 Usage 数据计算实际上下文 token 数</li>
 *   <li>使用 chars/4 启发式算法估算消息的 token 数量</li>
 *   <li>判断是否应该触发上下文压缩</li>
 *   <li>从助手指令消息中提取 usage 数据</li>
 * </ul>
 *
 * <p>Token 估算采用保守策略（倾向于高估），以确保压缩决策不会导致上下文溢出。
 *
 * <p><b>验证需求: 3.1, 3.2, 3.3</b>
 */
public final class CompactionUtils {

    private CompactionUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 从 usage 数据中计算上下文总 token 数。
     *
     * <p>优先使用原生 totalTokens 字段，如果不可用则回退到各分量之和。
     *
     * <p><b>验证需求: 3.1</b>
     *
     * @param usage LLM 调用的用量统计
     * @return 上下文总 token 数，usage 为 null 时返回 0
     */
    public static int calculateContextTokens(Usage usage) {
        if (usage == null) {
            return 0;
        }
        if (usage.totalTokens() > 0) {
            return usage.totalTokens();
        }
        return usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
    }

    /**
     * 使用 chars/4 启发式算法估算消息列表的 token 数量。
     *
     * <p>采用保守策略（倾向于高估 token 数），确保压缩决策安全。
     * 估算公式：chars / 4，最小值为 1。
     *
     * <p><b>验证需求: 3.2</b>
     *
     * @param messages 待估算的消息列表
     * @return 估算的 token 总数
     */
    public static int estimateTokens(List<? extends AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AgentMessage message : messages) {
            total += estimateTokens(message);
        }
        return total;
    }

    /**
     * 使用 chars/4 启发式算法估算单条消息的 token 数量。
     *
     * <p>根据消息的角色类型（user、assistant、toolResult 等）采用不同的字符估算策略。
     * 对于包装的 LLM 消息（MessageAdapter），会解析内部结构进行精确估算。
     * 图片内容按固定值（约 4800 字符 / 1200 token）估算。
     *
     * <p><b>验证需求: 3.2</b>
     *
     * @param message 待估算的消息
     * @return 估算的 token 数，最小为 1
     */
    public static int estimateTokens(AgentMessage message) {
        if (message == null) {
            return 0;
        }

        int chars = 0;
        String role = message.role();

        // 处理包装的 LLM 消息
        if (message instanceof MessageAdapter adapter) {
            Message llmMessage = adapter.message();
            return estimateLlmMessageTokens(llmMessage);
        }

        // 根据角色类型处理非包装消息
        switch (role) {
            case "user" -> chars = estimateUserMessageChars(message);
            case "assistant" -> chars = estimateAssistantMessageChars(message);
            case "toolResult" -> chars = estimateToolResultMessageChars(message);
            case "custom" -> chars = estimateCustomMessageChars(message);
            case "bashExecution" -> chars = estimateBashExecutionMessageChars(message);
            case "branchSummary", "compactionSummary" -> chars = estimateSummaryMessageChars(message);
            default -> chars = estimateGenericMessageChars(message);
        }

        return Math.max(1, (int) Math.ceil(chars / 4.0));
    }

    /**
     * 估算 LLM 消息对象的 token 数量。
     * 根据消息类型（UserMessage、AssistantMessage、ToolResultMessage）分别处理。
     */
    private static int estimateLlmMessageTokens(Message message) {
        int chars = 0;

        if (message instanceof UserMessage userMsg) {
            chars = estimateUserContentChars(userMsg.content());
        } else if (message instanceof AssistantMessage assistantMsg) {
            chars = estimateAssistantContentChars(assistantMsg.getContent());
        } else if (message instanceof ToolResultMessage toolResultMsg) {
            chars = estimateToolResultContentChars(toolResultMsg.content());
        }

        return Math.max(1, (int) Math.ceil(chars / 4.0));
    }

    /**
     * 估算用户消息内容的字符数。
     * 支持字符串内容和块列表（TextContent、UserContentBlock）两种格式。
     */
    private static int estimateUserContentChars(Object content) {
        if (content instanceof String str) {
            return str.length();
        } else if (content instanceof List<?> blocks) {
            int chars = 0;
            for (Object block : blocks) {
                if (block instanceof TextContent text) {
                    chars += text.text().length();
                } else if (block instanceof UserContentBlock ucb) {
                    chars += estimateUserContentBlockChars(ucb);
                }
            }
            return chars;
        }
        return content != null ? content.toString().length() : 0;
    }

    /**
     * 估算用户内容块（UserContentBlock）的字符数。
     * 文本内容按实际长度计算，图片内容按固定值（约 4800 字符 / 1200 token）估算。
     */
    private static int estimateUserContentBlockChars(UserContentBlock block) {
        if (block instanceof TextContent text) {
            return text.text().length();
        } else if (block instanceof ImageContent) {
            // 图片估算为 ~4800 字符（约 1200 token）
            return 4800;
        }
        return 0;
    }

    /**
     * 估算助手指令消息内容的字符数。
     * 包括文本内容、思考过程（ThinkingContent）和工具调用（ToolCall）的字符估算。
     */
    private static int estimateAssistantContentChars(List<AssistantContentBlock> content) {
        if (content == null) {
            return 0;
        }
        int chars = 0;
        for (AssistantContentBlock block : content) {
            if (block instanceof TextContent text) {
                chars += text.text().length();
            } else if (block instanceof ThinkingContent thinking) {
                chars += thinking.thinking().length();
            } else if (block instanceof ToolCall toolCall) {
                chars += toolCall.name().length();
                if (toolCall.arguments() != null) {
                    chars += toolCall.arguments().toString().length();
                }
            }
        }
        return chars;
    }

    /**
     * 估算工具结果消息内容的字符数。
     * 文本内容按实际长度计算，图片内容按固定值估算。
     */
    private static int estimateToolResultContentChars(List<UserContentBlock> content) {
        if (content == null) {
            return 0;
        }
        int chars = 0;
        for (UserContentBlock block : content) {
            if (block instanceof TextContent text) {
                chars += text.text().length();
            } else if (block instanceof ImageContent) {
                chars += 4800; // 图片估算为 ~4800 字符
            }
        }
        return chars;
    }

    /**
     * 估算非包装用户消息的字符数，通过 toString() 方法获取。
     */
    private static int estimateUserMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 估算非包装助手指令消息的字符数，通过 toString() 方法获取。
     */
    private static int estimateAssistantMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 估算非包装工具结果消息的字符数，通过 toString() 方法获取。
     */
    private static int estimateToolResultMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 估算自定义消息的字符数，通过 toString() 方法获取。
     */
    private static int estimateCustomMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 估算 bash 执行消息的字符数，通过 toString() 方法获取。
     * BashExecutionMessage 包含命令和输出两个字段。
     */
    private static int estimateBashExecutionMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 估算摘要消息（分支摘要或压缩摘要）的字符数，通过 toString() 方法获取。
     */
    private static int estimateSummaryMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 估算通用消息的字符数，通过 toString() 方法获取。
     * 作为兜底方法，处理未明确匹配的消息类型。
     */
    private static int estimateGenericMessageChars(AgentMessage message) {
        return message.toString().length();
    }

    /**
     * 检查是否应该根据上下文使用量触发压缩。
     *
     * <p>判断逻辑：当当前上下文 token 数超过（上下文窗口大小 - 预留 token 数）时触发压缩。
     * 预留 token 确保模型有足够的空间生成响应，防止上下文溢出。
     *
     * <p><b>验证需求: 3.3</b>
     *
     * @param contextTokens 当前上下文 token 数
     * @param contextWindow 模型的上下文窗口大小
     * @param reserveTokens 为响应预留的 token 数
     * @return 如果应触发压缩则返回 true
     */
    public static boolean shouldCompact(int contextTokens, int contextWindow, int reserveTokens) {
        if (contextWindow <= 0) {
            return false;
        }
        return contextTokens > contextWindow - reserveTokens;
    }

    /**
     * 从助手指令消息中获取 usage 数据（如果可用）。
     *
     * <p>跳过被中止（ABORTED）和错误（ERROR）的消息，因为它们的 usage 数据无效。
     * 仅从正常完成的助手指令消息中提取准确的 token 用量信息。
     *
     * @param message 待检查的代理消息
     * @return 如果可用则返回 Usage 对象，否则返回 null
     */
    public static Usage getAssistantUsage(AgentMessage message) {
        if (message == null) {
            return null;
        }

        if (!"assistant".equals(message.role())) {
            return null;
        }

        // 处理包装的助手指令消息
        if (message instanceof MessageAdapter adapter) {
            Message llmMessage = adapter.message();
            if (llmMessage instanceof AssistantMessage assistantMsg) {
                StopReason stopReason = assistantMsg.getStopReason();
                if (stopReason == StopReason.ABORTED || stopReason == StopReason.ERROR) {
                    return null;
                }
                return assistantMsg.getUsage();
            }
        }

        return null;
    }

    /**
     * 从消息列表中查找最后一个非中止的助手指令消息的 usage 数据。
     *
     * <p>从最新消息开始向前搜索，返回第一个找到的有效 usage 数据。
     * 用于获取最近一次成功的 LLM 调用的 token 用量，以判断是否需要压缩。
     *
     * @param messages 待搜索的消息列表
     * @return 如果找到则返回 Usage 对象，否则返回 null
     */
    public static Usage getLastAssistantUsage(List<? extends AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Usage usage = getAssistantUsage(messages.get(i));
            if (usage != null) {
                return usage;
            }
        }

        return null;
    }
}