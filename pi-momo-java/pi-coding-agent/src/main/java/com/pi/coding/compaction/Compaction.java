package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import com.pi.coding.session.*;

import java.util.*;

/**
 * 上下文压缩（Context Compaction）核心工具类。
 *
 * <p>提供上下文压缩的纯函数逻辑，包括：切割点检测、文件操作提取、文件列表计算与格式化。
 * 会话管理器负责 I/O 操作，压缩完成后会重新加载会话。
 *
 * <p>上下文压缩是 AI 编码助手长会话管理的核心功能：当上下文窗口接近上限时，
 * 将早期对话内容压缩为结构化摘要，保留关键信息（目标、进度、决策、文件操作），
 * 从而在有限的上下文窗口内持续工作。
 *
 * <p><b>验证需求: 3.4-3.15</b>
 */
public final class Compaction {

    private Compaction() {
        // 工具类，禁止实例化
    }

    // =========================================================================
    // 切割点检测（Task 5.2）
    // =========================================================================

    /**
     * 查找有效的切割点：返回用户、助手、自定义消息或 bashExecution 消息的索引列表。
     *
     * <p>永远不会在 toolResult（工具执行结果）处切割，因为工具结果必须紧跟其对应的工具调用。
     * 当我们在包含工具调用的助手指令消息处切割时，其后续的工具结果会被保留。
     *
     * <p><b>验证需求: 3.4, 3.5</b>
     *
     * @param entries    会话条目列表
     * @param startIndex 起始索引（包含）
     * @param endIndex   结束索引（不包含）
     * @return 有效切割点索引列表
     */
    static List<Integer> findValidCutPoints(List<SessionEntry> entries, int startIndex, int endIndex) {
        List<Integer> cutPoints = new ArrayList<>();

        for (int i = startIndex; i < endIndex; i++) {
            SessionEntry entry = entries.get(i);

            if (entry instanceof SessionMessageEntry sme) {
                String role = sme.message().role();
                // 有效的切割点角色：user、assistant、custom、bashExecution、branchSummary、compactionSummary
                // 绝不在 toolResult 处切割
                if (!"toolResult".equals(role)) {
                    cutPoints.add(i);
                }
            } else if (entry instanceof BranchSummaryEntry<?> || entry instanceof CustomMessageEntry<?>) {
                // branch_summary 和 custom_message 属于用户角色的消息，可作为有效切割点
                cutPoints.add(i);
            }
            // 跳过其他条目类型：ThinkingLevelChange、ModelChange、Compaction、Custom、Label、SessionInfo
        }

        return cutPoints;
    }

    /**
     * 查找包含给定条目索引的轮次（turn）的起始用户消息（或 bashExecution 消息）。
     *
     * <p>如果未找到轮次起始点，则返回 -1。
     * BashExecutionMessage 被视为用户消息以确定轮次边界。
     *
     * <p><b>验证需求: 3.7</b>
     *
     * @param entries    会话条目列表
     * @param entryIndex 待搜索的条目索引
     * @param startIndex 搜索的最小索引
     * @return 轮次起始索引，未找到则返回 -1
     */
    public static int findTurnStartIndex(List<SessionEntry> entries, int entryIndex, int startIndex) {
        for (int i = entryIndex; i >= startIndex; i--) {
            SessionEntry entry = entries.get(i);

            // branch_summary 和 custom_message 属于用户角色的消息，可作为轮次起始
            if (entry instanceof BranchSummaryEntry<?> || entry instanceof CustomMessageEntry<?>) {
                return i;
            }

            if (entry instanceof SessionMessageEntry sme) {
                String role = sme.message().role();
                if ("user".equals(role) || "bashExecution".equals(role)) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * 在会话条目中查找切割点，使得保留大约 keepRecentTokens 数量的 token。
     *
     * <p>算法：从最新条目开始向前遍历，累计估算的消息大小。
     * 当累计达到 >= keepRecentTokens 时停止，在该位置设置切割点。
     *
     * <p>可以在用户消息或助手指令消息处切割（绝不在工具结果处）。
     * 当在包含工具调用的助手指令消息处切割时，其后续的工具结果会被保留。
     *
     * <p><b>验证需求: 3.4, 3.5, 3.6, 3.7</b>
     *
     * @param entries          会话条目列表
     * @param startIndex       起始索引（包含）
     * @param endIndex         结束索引（不包含）
     * @param keepRecentTokens 要保留的近似 token 数量
     * @return 包含切割点信息的 CutPointResult
     */
    public static CutPointResult findCutPoint(
            List<SessionEntry> entries,
            int startIndex,
            int endIndex,
            int keepRecentTokens
    ) {
        List<Integer> cutPoints = findValidCutPoints(entries, startIndex, endIndex);

        if (cutPoints.isEmpty()) {
            return CutPointResult.noValidCutPoint(startIndex);
        }

        // 从最新条目开始向前遍历，累计估算的消息大小
        int accumulatedTokens = 0;
        int cutIndex = cutPoints.get(0); // 默认：从第一个有效切割点开始保留

        for (int i = endIndex - 1; i >= startIndex; i--) {
            SessionEntry entry = entries.get(i);

            if (!(entry instanceof SessionMessageEntry sme)) {
                continue;
            }

            // 估算该消息的大小
            int messageTokens = CompactionUtils.estimateTokens(sme.message());
            accumulatedTokens += messageTokens;

            // 检查是否超出了 token 预算
            if (accumulatedTokens >= keepRecentTokens) {
                // 查找在此索引处或之后最近的有效切割点
                for (int c = 0; c < cutPoints.size(); c++) {
                    if (cutPoints.get(c) >= i) {
                        cutIndex = cutPoints.get(c);
                        break;
                    }
                }
                break;
            }
        }

        // 从 cutIndex 向前扫描，包含非消息条目（如设置变更等）
        while (cutIndex > startIndex) {
            SessionEntry prevEntry = entries.get(cutIndex - 1);

            // 在压缩边界处停止
            if (prevEntry instanceof CompactionEntry<?>) {
                break;
            }

            // 如果遇到消息条目则停止
            if (prevEntry instanceof SessionMessageEntry) {
                break;
            }

            // 包含此非消息条目
            cutIndex--;
        }

        // 判断是否为拆分轮次（split turn）
        SessionEntry cutEntry = entries.get(cutIndex);
        boolean isUserMessage = false;

        if (cutEntry instanceof SessionMessageEntry sme) {
            isUserMessage = "user".equals(sme.message().role());
        } else if (cutEntry instanceof BranchSummaryEntry<?> || cutEntry instanceof CustomMessageEntry<?>) {
            // 这些是用户角色的消息
            isUserMessage = true;
        }

        int turnStartIndex = isUserMessage ? -1 : findTurnStartIndex(entries, cutIndex, startIndex);

        return new CutPointResult(
                cutIndex,
                turnStartIndex,
                !isUserMessage && turnStartIndex != -1
        );
    }

    /**
     * 简化的切割点查找方法，用于消息列表（而非会话条目列表）。
     *
     * <p>当处理的是扁平化的 AgentMessage 列表而非 SessionEntry 列表时使用此方法。
     *
     * @param messages         待搜索的消息列表
     * @param keepRecentTokens 要保留的近似 token 数量
     * @return 第一条要保留的消息的索引
     */
    public static int findCutPointInMessages(List<? extends AgentMessage> messages, int keepRecentTokens) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        // 查找有效切割点（非 toolResult）
        List<Integer> cutPoints = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String role = messages.get(i).role();
            if (!"toolResult".equals(role)) {
                cutPoints.add(i);
            }
        }

        if (cutPoints.isEmpty()) {
            return 0;
        }

        // 从后向前遍历，累计 token 数量
        int accumulatedTokens = 0;
        int cutIndex = cutPoints.get(0);

        for (int i = messages.size() - 1; i >= 0; i--) {
            int messageTokens = CompactionUtils.estimateTokens(messages.get(i));
            accumulatedTokens += messageTokens;

            if (accumulatedTokens >= keepRecentTokens) {
                // 查找在此索引处或之后最近的有效切割点
                for (int c = 0; c < cutPoints.size(); c++) {
                    if (cutPoints.get(c) >= i) {
                        cutIndex = cutPoints.get(c);
                        break;
                    }
                }
                break;
            }
        }

        return cutIndex;
    }

    // =========================================================================
    // 文件操作提取（Task 5.3）
    // =========================================================================

    /**
     * 从助手指令消息的工具调用中提取文件操作信息。
     *
     * <p><b>验证需求: 3.12</b>
     *
     * @param message 待提取的消息
     * @param fileOps 文件操作追踪器，用于更新
     */
    public static void extractFileOpsFromMessage(AgentMessage message, FileOperations fileOps) {
        if (message == null || fileOps == null) {
            return;
        }

        if (!"assistant".equals(message.role())) {
            return;
        }

        // 处理包装后的助手指令消息
        if (message instanceof MessageAdapter adapter) {
            Message llmMessage = adapter.message();
            if (llmMessage instanceof AssistantMessage assistantMsg) {
                extractFileOpsFromAssistantMessage(assistantMsg, fileOps);
            }
        }
    }

    /**
     * 从 AssistantMessage 中提取文件操作信息。
     *
     * @param message 助手指令消息
     * @param fileOps 文件操作追踪器
     */
    private static void extractFileOpsFromAssistantMessage(AssistantMessage message, FileOperations fileOps) {
        List<AssistantContentBlock> content = message.getContent();
        if (content == null) {
            return;
        }

        for (AssistantContentBlock block : content) {
            if (block instanceof ToolCall toolCall) {
                extractFileOpsFromToolCall(toolCall, fileOps);
            }
        }
    }

    /**
     * 从单个工具调用中提取文件操作信息。
     * 识别 read、write、edit 三种工具调用，分别记录到对应的文件集合中。
     *
     * @param toolCall 工具调用
     * @param fileOps  文件操作追踪器
     */
    private static void extractFileOpsFromToolCall(ToolCall toolCall, FileOperations fileOps) {
        Map<String, Object> args = toolCall.arguments();
        if (args == null) {
            return;
        }

        Object pathObj = args.get("path");
        if (!(pathObj instanceof String path) || path.isEmpty()) {
            return;
        }

        switch (toolCall.name()) {
            case "read" -> fileOps.addRead(path);
            case "write" -> fileOps.addWritten(path);
            case "edit" -> fileOps.addEdited(path);
        }
    }

    /**
     * 从消息列表中提取所有文件操作信息。
     *
     * @param messages 待提取的消息列表
     * @return 包含提取出的文件操作的 FileOperations 追踪器
     */
    public static FileOperations extractFileOperations(List<? extends AgentMessage> messages) {
        FileOperations fileOps = new FileOperations();
        if (messages != null) {
            for (AgentMessage message : messages) {
                extractFileOpsFromMessage(message, fileOps);
            }
        }
        return fileOps;
    }

    /**
     * 根据文件操作追踪器计算最终的文件列表。
     *
     * <p>返回只读文件列表（仅读取未修改）和修改文件列表。
     * 如果一个文件既被读取又被修改，则归入修改文件列表。
     *
     * @param fileOps 文件操作追踪器
     * @return 包含 "readFiles" 和 "modifiedFiles" 两个列表的 Map
     */
    public static Map<String, List<String>> computeFileLists(FileOperations fileOps) {
        Set<String> modified = new HashSet<>();
        modified.addAll(fileOps.getEdited());
        modified.addAll(fileOps.getWritten());

        List<String> readOnly = fileOps.getRead().stream()
                .filter(f -> !modified.contains(f))
                .sorted()
                .toList();

        List<String> modifiedFiles = modified.stream()
                .sorted()
                .toList();

        return Map.of(
                "readFiles", readOnly,
                "modifiedFiles", modifiedFiles
        );
    }

    /**
     * 将文件操作信息格式化为 XML 标签格式，用于追加到摘要中。
     *
     * @param readFiles     仅读取的文件列表
     * @param modifiedFiles 被修改的文件列表
     * @return 格式化后的字符串，无文件操作时返回空字符串
     */
    public static String formatFileOperations(List<String> readFiles, List<String> modifiedFiles) {
        List<String> sections = new ArrayList<>();

        if (readFiles != null && !readFiles.isEmpty()) {
            sections.add("<read-files>\n" + String.join("\n", readFiles) + "\n</read-files>");
        }

        if (modifiedFiles != null && !modifiedFiles.isEmpty()) {
            sections.add("<modified-files>\n" + String.join("\n", modifiedFiles) + "\n</modified-files>");
        }

        if (sections.isEmpty()) {
            return "";
        }

        return "\n\n" + String.join("\n\n", sections);
    }
}