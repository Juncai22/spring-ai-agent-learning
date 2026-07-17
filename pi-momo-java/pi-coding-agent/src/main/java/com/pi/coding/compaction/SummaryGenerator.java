package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 摘要生成器，使用 LLM 为上下文压缩生成结构化摘要。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li><b>初始摘要</b>：对一组消息生成全新的结构化摘要（目标、进度、决策、后续步骤）</li>
 *   <li><b>增量更新</b>：将新消息的信息合并到已有的摘要中，更新进度和状态</li>
 * </ul>
 *
 * <p>也支持轮次前缀摘要（当切割点位于轮次中间时，对轮次前半部分生成摘要）。
 * 摘要使用结构化格式，保留精确的文件路径、函数名和错误信息，确保后续轮次能无缝继续工作。
 *
 * <p><b>验证需求: 3.9, 3.10, 3.11, 3.13, 3.14, 3.15</b>
 */
public final class SummaryGenerator {

    private SummaryGenerator() {
        // 工具类，禁止实例化
    }

    /**
     * 摘要生成的系统提示词。
     * 指示 LLM 将对话作为内容进行摘要，而非继续对话。
     * 防止模型误将待摘要内容当作需要回复的对话。
     */
    public static final String SUMMARIZATION_SYSTEM_PROMPT = """
            You are a context summarization assistant. Your task is to read a conversation between a user and an AI coding assistant, then produce a structured summary following the exact format specified.

            Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.""";

    /**
     * 初始摘要生成的提示词模板。
     * 要求 LLM 生成包含以下章节的结构化摘要：
     * - Goal（目标）
     * - Constraints & Preferences（约束与偏好）
     * - Progress（进度：已完成/进行中/阻塞）
     * - Key Decisions（关键决策）
     * - Next Steps（后续步骤）
     * - Critical Context（关键上下文）
     *
     * <p>要求保留精确的文件路径、函数名和错误信息。
     */
    public static final String SUMMARIZATION_PROMPT = """
            The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

            Use this EXACT format:

            ## Goal
            [What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

            ## Constraints & Preferences
            - [Any constraints, preferences, or requirements mentioned by user]
            - [Or "(none)" if none were mentioned]

            ## Progress
            ### Done
            - [x] [Completed tasks/changes]

            ### In Progress
            - [ ] [Current work]

            ### Blocked
            - [Issues preventing progress, if any]

            ## Key Decisions
            - **[Decision]**: [Brief rationale]

            ## Next Steps
            1. [Ordered list of what should happen next]

            ## Critical Context
            - [Any data, examples, or references needed to continue]
            - [Or "(none)" if not applicable]

            Keep each section concise. Preserve exact file paths, function names, and error messages.""";

    /**
     * 增量更新摘要的提示词模板。
     * 将新消息的信息合并到 <previous-summary> 标签内的已有摘要中。
     * 规则：保留所有已有信息，添加新进展和决策，更新进度状态。
     */
    public static final String UPDATE_SUMMARIZATION_PROMPT = """
            The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

            Update the existing structured summary with new information. RULES:
            - PRESERVE all existing information from the previous summary
            - ADD new progress, decisions, and context from the new messages
            - UPDATE the Progress section: move items from "In Progress" to "Done" when completed
            - UPDATE "Next Steps" based on what was accomplished
            - PRESERVE exact file paths, function names, and error messages
            - If something is no longer relevant, you may remove it

            Use this EXACT format:

            ## Goal
            [Preserve existing goals, add new ones if the task expanded]

            ## Constraints & Preferences
            - [Preserve existing, add new ones discovered]

            ## Progress
            ### Done
            - [x] [Include previously done items AND newly completed items]

            ### In Progress
            - [ ] [Current work - update based on progress]

            ### Blocked
            - [Current blockers - remove if resolved]

            ## Key Decisions
            - **[Decision]**: [Brief rationale] (preserve all previous, add new)

            ## Next Steps
            1. [Update based on current state]

            ## Critical Context
            - [Preserve important context, add new if needed]

            Keep each section concise. Preserve exact file paths, function names, and error messages.""";

    /**
     * 轮次前缀摘要的提示词模板。
     * 当切割点位于轮次中间时，对轮次的前半部分（前缀）生成摘要，
     * 为保留的后半部分（后缀）提供上下文。
     * 包含：原始请求、早期进度、后缀上下文。
     */
    public static final String TURN_PREFIX_SUMMARIZATION_PROMPT = """
            This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.

            Summarize the prefix to provide context for the retained suffix:

            ## Original Request
            [What did the user ask for in this turn?]

            ## Early Progress
            - [Key decisions and work done in the prefix]

            ## Context for Suffix
            - [Information needed to understand the retained recent work]

            Be concise. Focus on what's needed to understand the kept suffix.""";

    /**
     * 序列化摘要中工具结果的最大字符数。
     * 超过此长度的工具结果会被截断，以防止摘要请求超出合理的 token 预算。
     */
    private static final int TOOL_RESULT_MAX_CHARS = 2000;

    /**
     * 截断文本到指定最大字符长度，用于摘要生成。
     * 在末尾添加截断说明，标记被截断的字符数。
     *
     * @param text     原始文本
     * @param maxChars 最大字符数
     * @return 截断后的文本，如果未超限则返回原文
     */
    static String truncateForSummary(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int truncatedChars = text.length() - maxChars;
        return text.substring(0, maxChars) + "\n\n[... " + truncatedChars + " more characters truncated]";
    }

    /**
     * 将 LLM 消息序列化为纯文本，用于摘要生成。
     *
     * <p>关键设计：将消息序列化为文本而非保持消息格式，防止 LLM 将待摘要内容
     * 误认为是需要继续的对话。工具结果会被截断以控制 token 预算。
     *
     * @param messages 待序列化的消息列表
     * @return 序列化后的对话文本
     */
    public static String serializeConversation(List<? extends AgentMessage> messages) {
        List<String> parts = new ArrayList<>();

        for (AgentMessage msg : messages) {
            String serialized = serializeMessage(msg);
            if (serialized != null && !serialized.isEmpty()) {
                parts.add(serialized);
            }
        }

        return String.join("\n\n", parts);
    }

    /**
     * 将单条消息序列化为文本，根据角色添加前缀标识。
     *
     * @param msg 待序列化的消息
     * @return 序列化后的文本，null 或空消息返回 null
     */
    private static String serializeMessage(AgentMessage msg) {
        if (msg == null) {
            return null;
        }

        // 处理包装的 LLM 消息
        if (msg instanceof MessageAdapter adapter) {
            return serializeLlmMessage(adapter.message());
        }

        // 根据角色类型处理非包装消息
        String role = msg.role();
        return switch (role) {
            case "user" -> "[User]: " + msg.toString();
            case "assistant" -> "[Assistant]: " + msg.toString();
            case "toolResult" -> "[Tool result]: " + truncateForSummary(msg.toString(), TOOL_RESULT_MAX_CHARS);
            case "custom" -> "[Custom]: " + msg.toString();
            case "bashExecution" -> "[Bash]: " + msg.toString();
            case "branchSummary", "compactionSummary" -> "[Summary]: " + msg.toString();
            default -> "[" + role + "]: " + msg.toString();
        };
    }

    /**
     * 将 LLM 消息对象序列化为文本。
     * 根据消息类型（UserMessage、AssistantMessage、ToolResultMessage）分别处理。
     */
    private static String serializeLlmMessage(Message msg) {
        if (msg instanceof UserMessage userMsg) {
            return serializeUserMessage(userMsg);
        } else if (msg instanceof AssistantMessage assistantMsg) {
            return serializeAssistantMessage(assistantMsg);
        } else if (msg instanceof ToolResultMessage toolResultMsg) {
            return serializeToolResultMessage(toolResultMsg);
        }
        return null;
    }

    /**
     * 序列化用户消息为文本格式。
     * 支持字符串内容和块列表（TextContent）两种格式。
     */
    private static String serializeUserMessage(UserMessage msg) {
        Object content = msg.content();
        String text;

        if (content instanceof String str) {
            text = str;
        } else if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof TextContent tc) {
                    sb.append(tc.text());
                }
            }
            text = sb.toString();
        } else {
            text = content != null ? content.toString() : "";
        }

        if (text.isEmpty()) {
            return null;
        }
        return "[User]: " + text;
    }

    /**
     * 序列化助手指令消息为文本格式。
     * 包含文本内容、思考过程（ThinkingContent）和工具调用（ToolCall）的序列化。
     * 工具调用会被格式化为函数名(参数名=参数值)的形式。
     */
    private static String serializeAssistantMessage(AssistantMessage msg) {
        List<String> parts = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        List<String> thinkingParts = new ArrayList<>();
        List<String> toolCalls = new ArrayList<>();

        List<AssistantContentBlock> content = msg.getContent();
        if (content != null) {
            for (AssistantContentBlock block : content) {
                if (block instanceof TextContent tc) {
                    textParts.add(tc.text());
                } else if (block instanceof ThinkingContent thinking) {
                    thinkingParts.add(thinking.thinking());
                } else if (block instanceof ToolCall toolCall) {
                    Map<String, Object> args = toolCall.arguments();
                    StringBuilder argsStr = new StringBuilder();
                    if (args != null) {
                        args.forEach((k, v) -> {
                            if (argsStr.length() > 0) argsStr.append(", ");
                            argsStr.append(k).append("=").append(v);
                        });
                    }
                    toolCalls.add(toolCall.name() + "(" + argsStr + ")");
                }
            }
        }

        if (!thinkingParts.isEmpty()) {
            parts.add("[Assistant thinking]: " + String.join("\n", thinkingParts));
        }
        if (!textParts.isEmpty()) {
            parts.add("[Assistant]: " + String.join("\n", textParts));
        }
        if (!toolCalls.isEmpty()) {
            parts.add("[Assistant tool calls]: " + String.join("; ", toolCalls));
        }

        return String.join("\n\n", parts);
    }

    /**
     * 序列化工具结果消息为文本格式，并截断过长内容。
     * 仅提取文本内容块，忽略图片等非文本内容。
     */
    private static String serializeToolResultMessage(ToolResultMessage msg) {
        List<UserContentBlock> content = msg.content();
        if (content == null || content.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (UserContentBlock block : content) {
            if (block instanceof TextContent tc) {
                sb.append(tc.text());
            }
        }

        String text = sb.toString();
        if (text.isEmpty()) {
            return null;
        }

        return "[Tool result]: " + truncateForSummary(text, TOOL_RESULT_MAX_CHARS);
    }

    /**
     * 构建摘要生成的提示文本。
     *
     * <p>将对话序列化为文本后，包裹在 &lt;conversation&gt; 标签中。
     * 如果有前一次摘要，则包裹在 &lt;previous-summary&gt; 标签中，并切换到增量更新模式。
     *
     * @param messages           待摘要的消息列表
     * @param previousSummary    前一次摘要内容（用于增量更新模式，可为 null）
     * @param customInstructions 额外的关注点指示（可为 null）
     * @return 构建完成的提示文本
     */
    public static String buildSummarizationPrompt(
            List<? extends AgentMessage> messages,
            String previousSummary,
            String customInstructions
    ) {
        // 将对话序列化为文本
        String conversationText = serializeConversation(messages);

        // 如果有前一次摘要则使用更新提示词，否则使用初始提示词
        String basePrompt = previousSummary != null ? UPDATE_SUMMARIZATION_PROMPT : SUMMARIZATION_PROMPT;

        if (customInstructions != null && !customInstructions.isEmpty()) {
            basePrompt = basePrompt + "\n\nAdditional focus: " + customInstructions;
        }

        // 构建提示文本，将对话包裹在标签中
        StringBuilder promptText = new StringBuilder();
        promptText.append("<conversation>\n").append(conversationText).append("\n</conversation>\n\n");

        if (previousSummary != null) {
            promptText.append("<previous-summary>\n").append(previousSummary).append("\n</previous-summary>\n\n");
        }

        promptText.append(basePrompt);

        return promptText.toString();
    }

    /**
     * 构建轮次前缀摘要的提示文本。
     * 当切割点位于轮次中间时，对轮次前半部分的消息生成摘要提示。
     *
     * @param messages 轮次前缀消息列表
     * @return 构建完成的提示文本
     */
    public static String buildTurnPrefixPrompt(List<? extends AgentMessage> messages) {
        String conversationText = serializeConversation(messages);
        return "<conversation>\n" + conversationText + "\n</conversation>\n\n" + TURN_PREFIX_SUMMARIZATION_PROMPT;
    }

    /**
     * 生成对话摘要（异步）。
     *
     * <p>当前实现为占位版本，返回一个简单的结构化摘要。
     * 实际实现中应调用 LLM 生成更精确的摘要。
     * 支持取消信号（CancellationSignal）以支持异步取消。
     *
     * <p><b>验证需求: 3.9, 3.10, 3.11, 3.14, 3.15</b>
     *
     * @param messages           待摘要的消息列表
     * @param previousSummary    前一次摘要（用于增量更新模式，可为 null）
     * @param fileOps            要包含的文件操作信息
     * @param customInstructions 额外的关注点指示（可为 null）
     * @param signal             取消信号（可为 null）
     * @return 包含生成摘要的 CompletableFuture
     */
    public static CompletableFuture<String> generateSummary(
            List<? extends AgentMessage> messages,
            String previousSummary,
            FileOperations fileOps,
            String customInstructions,
            CancellationSignal signal
    ) {
        return CompletableFuture.supplyAsync(() -> {
            // 检查是否已取消
            if (signal != null && signal.isCancelled()) {
                throw new RuntimeException("Summary generation cancelled");
            }

            // 构建提示词（供将来 LLM 调用使用）
            String prompt = buildSummarizationPrompt(messages, previousSummary, customInstructions);

            // 当前生成一个基于消息的简单摘要
            // 实际实现中应调用 LLM 生成
            StringBuilder summary = new StringBuilder();

            summary.append("## Goal\n");
            summary.append("[Session context preserved]\n\n");

            summary.append("## Progress\n");
            summary.append("### Done\n");
            summary.append("- [x] Previous conversation summarized\n\n");

            summary.append("## Key Decisions\n");
            summary.append("- **Context compacted**: Session history summarized to fit context window\n\n");

            summary.append("## Next Steps\n");
            summary.append("1. Continue with the current task\n");

            // 追加文件操作信息
            if (fileOps != null && !fileOps.isEmpty()) {
                Map<String, List<String>> fileLists = Compaction.computeFileLists(fileOps);
                String fileOpsStr = Compaction.formatFileOperations(
                        fileLists.get("readFiles"),
                        fileLists.get("modifiedFiles")
                );
                summary.append(fileOpsStr);
            }

            return summary.toString();
        });
    }

    /**
     * 生成轮次前缀摘要（异步）。
     * 当切割点位于轮次中间时，对轮次前半部分的消息生成摘要。
     * 支持取消信号以支持异步取消。
     *
     * @param messages 轮次前缀消息列表
     * @param signal   取消信号（可为 null）
     * @return 包含生成摘要的 CompletableFuture
     */
    public static CompletableFuture<String> generateTurnPrefixSummary(
            List<? extends AgentMessage> messages,
            CancellationSignal signal
    ) {
        return CompletableFuture.supplyAsync(() -> {
            // 检查是否已取消
            if (signal != null && signal.isCancelled()) {
                throw new RuntimeException("Turn prefix summary generation cancelled");
            }

            // 构建提示词（供将来 LLM 调用使用）
            String prompt = buildTurnPrefixPrompt(messages);

            // 当前生成一个简单摘要
            // 实际实现中应调用 LLM 生成
            StringBuilder summary = new StringBuilder();

            summary.append("## Original Request\n");
            summary.append("[Turn prefix context preserved]\n\n");

            summary.append("## Early Progress\n");
            summary.append("- Initial work in this turn summarized\n\n");

            summary.append("## Context for Suffix\n");
            summary.append("- Continue with the retained recent work\n");

            return summary.toString();
        });
    }
}